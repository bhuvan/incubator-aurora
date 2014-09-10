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
package org.apache.aurora.scheduler.app;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.annotation.Nonnegative;
import javax.inject.Inject;
import javax.inject.Singleton;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.Maps;
import com.google.inject.AbstractModule;
import com.google.inject.BindingAnnotation;
import com.google.inject.Module;
import com.google.inject.name.Names;
import com.twitter.common.application.AbstractApplication;
import com.twitter.common.application.AppLauncher;
import com.twitter.common.application.Lifecycle;
import com.twitter.common.application.modules.LocalServiceRegistry;
import com.twitter.common.application.modules.LogModule;
import com.twitter.common.application.modules.StatsModule;
import com.twitter.common.args.Arg;
import com.twitter.common.args.CmdLine;
import com.twitter.common.args.constraints.NotEmpty;
import com.twitter.common.args.constraints.NotNull;
import com.twitter.common.inject.Bindings;
import com.twitter.common.logging.RootLogConfig;
import com.twitter.common.zookeeper.Group;
import com.twitter.common.zookeeper.SingletonService;
import com.twitter.common.zookeeper.SingletonService.LeadershipListener;
import com.twitter.common.zookeeper.guice.client.ZooKeeperClientModule;
import com.twitter.common.zookeeper.guice.client.ZooKeeperClientModule.ClientConfig;
import com.twitter.common.zookeeper.guice.client.flagged.FlaggedClientConfig;

import org.apache.aurora.auth.CapabilityValidator;
import org.apache.aurora.auth.SessionValidator;
import org.apache.aurora.auth.UnsecureAuthModule;
import org.apache.aurora.scheduler.DriverFactory;
import org.apache.aurora.scheduler.DriverFactory.DriverFactoryImpl;
import org.apache.aurora.scheduler.MesosTaskFactory.ExecutorConfig;
import org.apache.aurora.scheduler.SchedulerLifecycle;
import org.apache.aurora.scheduler.cron.quartz.CronModule;
import org.apache.aurora.scheduler.http.GatekeeperHost;
import org.apache.aurora.scheduler.http.GatekeeperPort;
import org.apache.aurora.scheduler.log.mesos.MesosLogStreamModule;
import org.apache.aurora.scheduler.storage.backup.BackupModule;
import org.apache.aurora.scheduler.storage.db.DbModule;
import org.apache.aurora.scheduler.storage.db.MigrationModule;
import org.apache.aurora.scheduler.storage.log.LogStorage;
import org.apache.aurora.scheduler.storage.log.LogStorageModule;
import org.apache.aurora.scheduler.storage.log.SnapshotStoreImpl;
import org.apache.aurora.scheduler.storage.mem.MemStorage.Delegated;
import org.apache.aurora.scheduler.storage.mem.MemStorageModule;
import org.apache.aurora.scheduler.thrift.ThriftModule;
import org.apache.aurora.scheduler.thrift.auth.ThriftAuthModule;

/**
 * Launcher for the aurora scheduler.
 */
public class SchedulerMain extends AbstractApplication {

  private static final Logger LOG = Logger.getLogger(SchedulerMain.class.getName());

  @NotNull
  @CmdLine(name = "cluster_name", help = "Name to identify the cluster being served.")
  private static final Arg<String> CLUSTER_NAME = Arg.create();

  @CmdLine(name = "gatekeeper_host", help = "Gatekeeper host to publish in zookeeper.")
  private static final Arg<String> GATEKEEPER_HOST = Arg.create("");

  @CmdLine(name = "gatekeeper_port", help = "Gatekeeper port to publish in zookeeper.")
  private static final Arg<Integer> GATEKEEPER_PORT = Arg.create(0);

  @CmdLine(name = "gatekeeper_scheme", help = "Gatekeeper HTTP scheme. Default: https")
  private static final Arg<String> GATEKEEPER_SCHEME = Arg.create("https");

  @NotNull
  @NotEmpty
  @CmdLine(name = "serverset_path", help = "ZooKeeper ServerSet path to register at.")
  private static final Arg<String> SERVERSET_PATH = Arg.create();

  @NotNull
  @CmdLine(name = "thermos_executor_path", help = "Path to the thermos executor launch script.")
  private static final Arg<String> THERMOS_EXECUTOR_PATH = Arg.create();

  @CmdLine(name = "auth_module",
      help = "A Guice module to provide auth bindings. NOTE: The default is unsecure.")
  private static final Arg<? extends Class<? extends Module>> AUTH_MODULE =
      Arg.create(UnsecureAuthModule.class);

  private static final Iterable<Class<?>> AUTH_MODULE_CLASSES = ImmutableList.<Class<?>>builder()
      .add(SessionValidator.class)
      .add(CapabilityValidator.class)
      .build();

  // TODO(Suman Karumuri): Pass in AUTH as extra module
  @CmdLine(name = "extra_modules",
      help = "A list of modules that provide additional functionality.")
  private static final Arg<List<Class<? extends Module>>> EXTRA_MODULES =
      Arg.create((List<Class<? extends Module>>) ImmutableList.<Class<? extends Module>>of());

  // TODO(Suman Karumuri): Rename viz_job_url_prefix to stats_job_url_prefix for consistency.
  @CmdLine(name = "viz_job_url_prefix", help = "URL prefix for job container stats.")
  private static final Arg<String> STATS_URL_PREFIX = Arg.create("");

  @Inject private SingletonService schedulerService;
  @Inject private LocalServiceRegistry serviceRegistry;
  @Inject private SchedulerLifecycle schedulerLifecycle;
  @Inject private Lifecycle appLifecycle;
  @Inject private Optional<RootLogConfig.Configuration> glogConfig;

  private static Iterable<? extends Module> getExtraModules() {
    Builder<Module> modules = ImmutableList.builder();
    modules.add(Modules.wrapInPrivateModule(AUTH_MODULE.get(), AUTH_MODULE_CLASSES));

    for (Class<? extends Module> moduleClass : EXTRA_MODULES.get()) {
      modules.add(Modules.getModule(moduleClass));
    }

    return modules.build();
  }

  @VisibleForTesting
  Iterable<? extends Module> getModules(
      String clusterName,
      String serverSetPath,
      ClientConfig zkClientConfig,
      String statsURLPrefix) {

    return ImmutableList.<Module>builder()
        .add(new LogModule())
        .add(new StatsModule())
        .add(new AppModule(clusterName, serverSetPath, zkClientConfig, statsURLPrefix))
        .addAll(getExtraModules())
        .add(getPersistentStorageModule())
        .add(new MemStorageModule(Bindings.annotatedKeyFactory(LogStorage.WriteBehind.class)))
        .add(new CronModule())
        .add(new DbModule(Bindings.annotatedKeyFactory(Delegated.class)))
        .add(new MigrationModule(
            Bindings.annotatedKeyFactory(LogStorage.WriteBehind.class),
            Bindings.annotatedKeyFactory(Delegated.class))
        )
        .add(new ThriftModule())
        .add(new ThriftAuthModule())
        .build();
  }

  protected Module getPersistentStorageModule() {
    return new AbstractModule() {
      @Override
      protected void configure() {
        install(new LogStorageModule());
      }
    };
  }

  protected Module getMesosModules() {
    final ClientConfig zkClientConfig = FlaggedClientConfig.create();
    return new AbstractModule() {
      @Override
      protected void configure() {
        bind(DriverFactory.class).to(DriverFactoryImpl.class);
        bind(DriverFactoryImpl.class).in(Singleton.class);
        install(new MesosLogStreamModule(zkClientConfig));
      }
    };
  }

  @Override
  public Iterable<? extends Module> getModules() {
    ClientConfig zkClientConfig = FlaggedClientConfig.create();
    return ImmutableList.<Module>builder()
        .add(new BackupModule(SnapshotStoreImpl.class))
        .addAll(
            getModules(
                CLUSTER_NAME.get(),
                SERVERSET_PATH.get(),
                zkClientConfig,
                STATS_URL_PREFIX.get()))
        .add(new ZooKeeperClientModule(zkClientConfig))
        .add(new AbstractModule() {
          @Override
          protected void configure() {
            bind(ExecutorConfig.class).toInstance(new ExecutorConfig(THERMOS_EXECUTOR_PATH.get()));
          }
        })
        .add(getMesosModules())
        .build();
  }

  @Override
  public void run() {
    if (glogConfig.isPresent()) {
      // Setup log4j to match our jul glog config in order to pick up zookeeper logging.
      Log4jConfigurator.configureConsole(glogConfig.get());
    } else {
      LOG.warning("Running without expected glog configuration.");
    }

    LeadershipListener leaderListener = schedulerLifecycle.prepare();
    Map<String, InetSocketAddress> registrySockets = Maps.newHashMap();
    Optional<InetSocketAddress> optHttpSocket;

    if (!GATEKEEPER_HOST.get().isEmpty() && GATEKEEPER_PORT.get() > 0) {
        bind(String.class).annotatedWith(GatekeeperHost.class).toInstance("scheduler");
        bind(Integer.class).annotatedWith(GatekeeperPort.class).toInstance(8081);

        // use gatekeeper host/port to publish to zookeeper
        LOG.info("Gatekeeper host/port defined, registering with zookeeper. ");
        LOG.info("    Gatekeeper host :" + GATEKEEPER_HOST.get() + ": port :" + GATEKEEPER_PORT.get());
        InetSocketAddress httpSocket = new InetSocketAddress(GATEKEEPER_HOST.get(), GATEKEEPER_PORT.get());
        optHttpSocket = Optional.fromNullable(httpSocket);
        if (!optHttpSocket.isPresent()) {
          throw new IllegalStateException("No gatekeeper service running in front of scheduler.");
        }

        registrySockets.put(GATEKEEPER_SCHEME.get(), httpSocket);
    }
    else {
        // no gatekeeper, use scheduler host/port to publish in zookeeper
        LOG.info("NO Gatekeeper. Scheduler host/port defined, registering with zookeeper.");
        optHttpSocket = Optional.fromNullable(serviceRegistry.getAuxiliarySockets().get("http"));
        if (!optHttpSocket.isPresent()) {
            throw new IllegalStateException("No HTTP service registered with LocalServiceRegistry.");
        }
        registrySockets = serviceRegistry.getAuxiliarySockets();
    }

    LOG.info("        HTTP socket :" + optHttpSocket.get());
    try {
      schedulerService.lead(
          optHttpSocket.get(),
          registrySockets,
          leaderListener);
    } catch (Group.WatchException e) {
      throw new IllegalStateException("Failed to watch group and lead service.", e);
    } catch (Group.JoinException e) {
      throw new IllegalStateException("Failed to join scheduler service group.", e);
    } catch (InterruptedException e) {
      throw new IllegalStateException("Interrupted while joining scheduler service group.", e);
    }

    appLifecycle.awaitShutdown();
  }

  public static void main(String[] args) {
    AppLauncher.launch(SchedulerMain.class, args);
  }
}
