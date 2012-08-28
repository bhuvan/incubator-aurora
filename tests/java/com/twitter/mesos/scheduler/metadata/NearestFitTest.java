package com.twitter.mesos.scheduler.metadata;

import java.util.Set;

import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Test;

import com.twitter.common.quantity.Amount;
import com.twitter.common.quantity.Time;
import com.twitter.common.util.testing.FakeTicker;
import com.twitter.mesos.scheduler.SchedulingFilter.Veto;
import com.twitter.mesos.scheduler.events.TaskPubsubEvent.Deleted;
import com.twitter.mesos.scheduler.events.TaskPubsubEvent.Vetoed;

import static org.junit.Assert.assertEquals;

public class NearestFitTest {

  private static final Veto ALMOST = new Veto("Almost", 1);
  private static final Veto NOPE = new Veto("Nope", 5);
  private static final Veto NO_CHANCE = new Veto("No chance", 1000);
  private static final Veto DEDICATED = Veto.dedicated("Dedicated");

  private static final String TASK = "taskId";

  private FakeTicker ticker;
  private NearestFit nearest;

  @Before
  public void setUp() {
    ticker = new FakeTicker();
    nearest = new NearestFit(ticker);
  }

  @Test
  public void testNoReason() {
    assertNearest();
  }

  @Test
  public void testMultipleVetoes() {
    vetoed(ALMOST, NOPE);
    assertNearest(ALMOST, NOPE);
    // Even though the aggregate score for NO_CHANCE is higher than ALMOST and NOPE,
    // NO_CHANCE becomes the pending reason since we consider one vector smaller than two
    // (regardless of magnitude).
    vetoed(NO_CHANCE);
    assertNearest(NO_CHANCE);
  }

  @Test
  public void testScoring() {
    vetoed(NO_CHANCE);
    assertNearest(NO_CHANCE);
    vetoed(ALMOST);
    assertNearest(ALMOST);
    vetoed(NO_CHANCE);
    assertNearest(ALMOST);
  }

  @Test
  public void testRemove() {
    vetoed(NO_CHANCE);
    nearest.remove(new Deleted(ImmutableSet.of(TASK)));
    assertNearest();
  }

  @Test
  public void testExpiration() {
    vetoed(ALMOST);
    assertNearest(ALMOST);
    ticker.advance(NearestFit.EXPIRATION);
    ticker.advance(Amount.of(1L, Time.SECONDS));
    assertNearest();
  }

  private Set<Veto> vetoes(Veto... vetoes) {
    return ImmutableSet.<Veto>builder().add(vetoes).build();
  }

  private void vetoed(Veto... vetoes) {
    nearest.vetoed(new Vetoed(TASK, ImmutableSet.<Veto>builder().add(vetoes).build()));
  }

  private void assertNearest(Veto... vetoes) {
    assertEquals(vetoes(vetoes), nearest.getNearestFit(TASK));
  }
}