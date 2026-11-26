package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.inventory.hr.domain.model.WorkerJoinRequest;
import com.inventory.hr.domain.model.enums.JoinOutcome;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Worker-app R14.2: every worker event increments exactly one meter by exactly one, with the tags
 * the design names, and nothing else moves. Runs against a real Micrometer registry so the tag keys
 * and values are the ones a dashboard would see.
 */
class HrWorkerCountersTest {

  private static final Tags HR = Tags.of("module", HrMetricsConstants.MODULE);

  private SimpleMeterRegistry registry;
  private HrWorkerCounters counters;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    counters = new HrWorkerCounters(new MetricsWrapper(registry));
  }

  @Test
  void aWorkerSignupMovesOnlyTheSignupCounter() {
    counters.workerSignedUp();

    assertEquals(1.0, count(HrMetricsConstants.WORKER_SIGNUPS_TOTAL, HR));
    assertOnlyMeters(HrMetricsConstants.WORKER_SIGNUPS_TOTAL);
    assertEquals(1, registry.getMeters().size(), "one series");
  }

  @Test
  void anAppLoginIsTaggedWithItsResult() {
    counters.appLogin(true);
    counters.appLogin(true);
    counters.appLogin(false);

    assertEquals(2.0, count(HrMetricsConstants.WORKER_APP_LOGINS_TOTAL,
        HR.and(HrMetricsConstants.TAG_RESULT, "success")));
    assertEquals(1.0, count(HrMetricsConstants.WORKER_APP_LOGINS_TOTAL,
        HR.and(HrMetricsConstants.TAG_RESULT, "error")));
    assertOnlyMeters(HrMetricsConstants.WORKER_APP_LOGINS_TOTAL);
    assertEquals(2, registry.getMeters().size(), "one series per result");
  }

  @Test
  void aLookupIsTaggedFoundOrNotFound() {
    counters.lookup(true);
    counters.lookup(false);
    counters.lookup(false);

    assertEquals(1.0, count(HrMetricsConstants.WORKER_LOOKUPS_TOTAL,
        HR.and(HrMetricsConstants.TAG_RESULT, "found")));
    assertEquals(2.0, count(HrMetricsConstants.WORKER_LOOKUPS_TOTAL,
        HR.and(HrMetricsConstants.TAG_RESULT, "not_found")));
    assertOnlyMeters(HrMetricsConstants.WORKER_LOOKUPS_TOTAL);
  }

  @Test
  void everyJoinRequestEventIsOneIncrementOnOneSeries() {
    WorkerJoinRequest join = request("shop-1");

    counters.joinRequestCreated(join);
    counters.joinRequestWithdrawn(join);
    counters.joinRequestApproved(join, JoinOutcome.APPROVED_CREATED);
    counters.joinRequestRejected(join);
    counters.joinRequestCreated(join);
    counters.joinRequestApproved(join, JoinOutcome.APPROVED_LINKED);

    assertEquals(2.0, joinCount("shop-1", "created"));
    assertEquals(1.0, joinCount("shop-1", "withdrawn"));
    assertEquals(1.0, joinCount("shop-1", "approved_created"));
    assertEquals(1.0, joinCount("shop-1", "rejected"));
    assertEquals(1.0, joinCount("shop-1", "approved_linked"));
    assertOnlyMeters(HrMetricsConstants.WORKER_JOIN_REQUESTS_TOTAL);
    assertEquals(5, registry.getMeters().size(), "five events, five distinct series, no kind tag");
    assertEquals(6.0, registry.find(HrMetricsConstants.WORKER_JOIN_REQUESTS_TOTAL).counters()
        .stream().mapToDouble(Counter::count).sum(), "six increments in total");
    for (Meter meter : registry.getMeters()) {
      assertNull(meter.getId().getTag("kind"), "no kind tag (D11): " + meter.getId());
    }
  }

  @Test
  void approvalEventFollowsTheOutcome() {
    // "Create new" is a created employee, "Link to existing" a linked one: the event names the
    // outcome of the approval, nothing about the request itself.
    counters.joinRequestApproved(request("shop-1"), JoinOutcome.APPROVED_CREATED);
    counters.joinRequestApproved(request("shop-1"), JoinOutcome.APPROVED_LINKED);
    counters.joinRequestApproved(request("shop-1"), JoinOutcome.APPROVED_LINKED);

    assertEquals(1.0, joinCount("shop-1", "approved_created"));
    assertEquals(2.0, joinCount("shop-1", "approved_linked"));
  }

  @Test
  void theShopTagKeepsShopsApart() {
    counters.joinRequestCreated(request("shop-1"));
    counters.joinRequestCreated(request("shop-2"));
    counters.joinRequestCreated(request("shop-2"));

    assertEquals(1.0, joinCount("shop-1", "created"));
    assertEquals(2.0, joinCount("shop-2", "created"));
  }

  @Test
  void aRequestWithoutAShopStillLandsOnOneBoundedSeries() {
    counters.joinRequestCreated(request(null));

    assertEquals(1.0, joinCount(HrWorkerCounters.UNKNOWN_SHOP, "created"));
    assertEquals(1, registry.getMeters().size());
  }

  @Test
  void theTagValueSetsAreTheDesignsAndNothingElse() {
    assertEquals(List.of("created", "approved_created", "approved_linked", "rejected", "withdrawn"),
        List.of(HrMetricsConstants.WorkerJoinEvent.CREATED,
            HrMetricsConstants.WorkerJoinEvent.APPROVED_CREATED,
            HrMetricsConstants.WorkerJoinEvent.APPROVED_LINKED,
            HrMetricsConstants.WorkerJoinEvent.REJECTED,
            HrMetricsConstants.WorkerJoinEvent.WITHDRAWN));
    assertEquals(List.of("found", "not_found"),
        List.of(HrMetricsConstants.WorkerLookupResult.FOUND,
            HrMetricsConstants.WorkerLookupResult.NOT_FOUND));
    assertEquals(List.of("success", "error"),
        List.of(HrMetricsConstants.WorkerLoginResult.SUCCESS,
            HrMetricsConstants.WorkerLoginResult.ERROR));
  }

  // ---- helpers --------------------------------------------------------------------------------

  private static WorkerJoinRequest request(String shopId) {
    WorkerJoinRequest r = new WorkerJoinRequest();
    r.setId("req-1");
    r.setShopId(shopId);
    r.setUserId("worker-1");
    return r;
  }

  private static Tags joinTags(String shop, String event) {
    return HR.and(HrMetricsConstants.TAG_SHOP, shop).and(HrMetricsConstants.TAG_EVENT, event);
  }

  private double joinCount(String shop, String event) {
    return count(HrMetricsConstants.WORKER_JOIN_REQUESTS_TOTAL, joinTags(shop, event));
  }

  private double count(String name, Tags tags) {
    Counter counter = find(name, tags);
    assertNotNull(counter, name + tags + " exists");
    assertEquals(tags.stream().count(), counter.getId().getTags().size(),
        "no tag beyond the design's on " + name);
    return counter.count();
  }

  private Counter find(String name, Tags tags) {
    return registry.find(name).tags(tags).counter();
  }

  /** Every meter in the registry carries this name: no other counter moved. */
  private void assertOnlyMeters(String name) {
    for (Meter meter : registry.getMeters()) {
      assertEquals(name, meter.getId().getName(), "unexpected meter " + meter.getId());
    }
  }
}
