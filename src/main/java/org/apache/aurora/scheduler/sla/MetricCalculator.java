/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aurora.scheduler.sla;

import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Range;
import com.twitter.common.inject.TimedInterceptor.Timed;
import com.twitter.common.stats.StatsProvider;
import com.twitter.common.util.Clock;

import org.apache.aurora.scheduler.base.Query;
import org.apache.aurora.scheduler.base.Tasks;
import org.apache.aurora.scheduler.sla.SlaAlgorithm.AlgorithmType;
import org.apache.aurora.scheduler.sla.SlaGroup.GroupType;
import org.apache.aurora.scheduler.storage.Storage;
import org.apache.aurora.scheduler.storage.entities.IScheduledTask;
import org.apache.aurora.scheduler.storage.entities.ITaskConfig;

import static java.util.Objects.requireNonNull;

import static org.apache.aurora.scheduler.sla.SlaAlgorithm.AlgorithmType.AGGREGATE_PLATFORM_UPTIME;
import static org.apache.aurora.scheduler.sla.SlaAlgorithm.AlgorithmType.JOB_UPTIME_50;
import static org.apache.aurora.scheduler.sla.SlaAlgorithm.AlgorithmType.JOB_UPTIME_75;
import static org.apache.aurora.scheduler.sla.SlaAlgorithm.AlgorithmType.JOB_UPTIME_90;
import static org.apache.aurora.scheduler.sla.SlaAlgorithm.AlgorithmType.JOB_UPTIME_95;
import static org.apache.aurora.scheduler.sla.SlaAlgorithm.AlgorithmType.JOB_UPTIME_99;
import static org.apache.aurora.scheduler.sla.SlaAlgorithm.AlgorithmType.MEDIAN_TIME_TO_ASSIGNED;
import static org.apache.aurora.scheduler.sla.SlaAlgorithm.AlgorithmType.MEDIAN_TIME_TO_RUNNING;
import static org.apache.aurora.scheduler.sla.SlaGroup.GroupType.CLUSTER;
import static org.apache.aurora.scheduler.sla.SlaGroup.GroupType.JOB;
import static org.apache.aurora.scheduler.sla.SlaGroup.GroupType.RESOURCE_CPU;
import static org.apache.aurora.scheduler.sla.SlaGroup.GroupType.RESOURCE_DISK;
import static org.apache.aurora.scheduler.sla.SlaGroup.GroupType.RESOURCE_RAM;

/**
 * Responsible for calculating and exporting SLA metrics.
 */
class MetricCalculator implements Runnable {

  private static final Multimap<AlgorithmType, GroupType> METRICS =
      ImmutableMultimap.<AlgorithmType, GroupType>builder()
          .put(JOB_UPTIME_50, JOB)
          .put(JOB_UPTIME_75, JOB)
          .put(JOB_UPTIME_90, JOB)
          .put(JOB_UPTIME_95, JOB)
          .put(JOB_UPTIME_99, JOB)
          .putAll(AGGREGATE_PLATFORM_UPTIME, JOB, CLUSTER)
          .putAll(MEDIAN_TIME_TO_ASSIGNED, JOB, CLUSTER, RESOURCE_CPU, RESOURCE_RAM, RESOURCE_DISK)
          .putAll(MEDIAN_TIME_TO_RUNNING, JOB, CLUSTER, RESOURCE_CPU, RESOURCE_RAM, RESOURCE_DISK)
          .build();

  private static final Predicate<ITaskConfig> IS_SERVICE =
      new Predicate<ITaskConfig>() {
        @Override
        public boolean apply(ITaskConfig task) {
          return task.isIsService();
        }
      };

  private final LoadingCache<String, Counter> metricCache;
  private final Storage storage;
  private final Clock clock;
  private final MetricCalculatorSettings settings;

  static class MetricCalculatorSettings {
    private final long refreshRateMs;

    MetricCalculatorSettings(long refreshRateMs) {
      this.refreshRateMs = refreshRateMs;
    }

    long getRefreshRateMs() {
      return refreshRateMs;
    }
  }

  private static class Counter implements Supplier<Number> {
    private final AtomicReference<Number> value = new AtomicReference<>((Number) 0);
    private final StatsProvider statsProvider;
    private boolean exported;

    Counter(StatsProvider statsProvider) {
      this.statsProvider = statsProvider;
    }

    @Override
    public Number get() {
      return value.get();
    }

    private void set(String name, Number newValue) {
      if (!exported) {
        statsProvider.makeGauge(name, this);
        exported = true;
      }
      value.set(newValue);
    }
  }

  @Inject
  MetricCalculator(
      Storage storage,
      Clock clock,
      MetricCalculatorSettings settings,
      final StatsProvider statsProvider) {

    this.storage = requireNonNull(storage);
    this.clock = requireNonNull(clock);
    this.settings = requireNonNull(settings);

    requireNonNull(statsProvider);
    this.metricCache = CacheBuilder.newBuilder().build(
        new CacheLoader<String, Counter>() {
          public Counter load(String key) {
            return new Counter(statsProvider.untracked());
          }
        });
  }

  @Timed("sla_stats_computation")
  @Override
  public void run() {
    List<IScheduledTask> tasks =
        FluentIterable.from(Storage.Util.weaklyConsistentFetchTasks(storage, Query.unscoped()))
            .filter(Predicates.compose(
                Predicates.and(Tasks.IS_PRODUCTION, IS_SERVICE),
                Tasks.SCHEDULED_TO_INFO)).toList();

    long nowMs = clock.nowMillis();
    long intervalStartMs = nowMs - settings.getRefreshRateMs();

    for (Entry<AlgorithmType, GroupType> slaMetric : METRICS.entries()) {
      for (Entry<String, Collection<IScheduledTask>> namedGroup
          : slaMetric.getValue().getSlaGroup().createNamedGroups(tasks).asMap().entrySet()) {

        AlgorithmType algoType = slaMetric.getKey();
        String metricName = namedGroup.getKey() + algoType.getAlgorithmName();
        metricCache.getUnchecked(metricName)
            .set(metricName, algoType.getAlgorithm().calculate(
                namedGroup.getValue(),
                Range.closedOpen(intervalStartMs, nowMs)));
      }
    }
  }
}
