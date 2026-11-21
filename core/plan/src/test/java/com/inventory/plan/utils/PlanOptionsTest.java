package com.inventory.plan.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.constants.ErrorCode;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.PlanAction;
import com.inventory.plan.rest.dto.response.PlanOptionResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanOptionsTest {

  private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
  private static final Plan STARTER = plan("p1", "STARTER", "2999");
  private static final Plan GROWTH = plan("p2", "GROWTH", "5999");
  private static final Plan PROFESSIONAL = plan("p3", "PROFESSIONAL", "8499");
  private static final List<Plan> CATALOGUE = List.of(STARTER, GROWTH, PROFESSIONAL);

  @Test
  void aLowerPlanIsBuyAndAHigherOneUpgradeBothWaitingForThePaidPlanToEnd() {
    Instant expiry = NOW.plus(200, ChronoUnit.DAYS);

    List<PlanOptionResponse> options = PlanOptions.of(CATALOGUE, GROWTH, expiry, NOW);

    assertEquals(PlanAction.BUY, options.get(0).getAction());
    assertEquals(PlanAction.CURRENT, options.get(1).getAction());
    assertEquals(PlanAction.UPGRADE, options.get(2).getAction());
    assertTrue(options.get(1).isActionAvailable());
    assertNull(options.get(1).getUnavailableReason());
    for (PlanOptionResponse other : List.of(options.get(0), options.get(2))) {
      assertFalse(other.isActionAvailable());
      assertEquals(ErrorCode.PLAN_CHANGE_NOT_ALLOWED.getMessage(), other.getUnavailableReason());
      assertEquals(expiry, other.getAvailableFrom());
    }
  }

  @Test
  void onTheTopPlanEveryOtherPlanIsBuyNeverUpgrade() {
    List<PlanOptionResponse> options = PlanOptions.of(CATALOGUE, PROFESSIONAL, NOW.plus(30, ChronoUnit.DAYS), NOW);

    assertEquals(List.of(PlanAction.BUY, PlanAction.BUY, PlanAction.CURRENT),
        options.stream().map(PlanOptionResponse::getAction).toList());
  }

  @Test
  void afterThePaidPlanEndsEveryActionIsAvailable() {
    List<PlanOptionResponse> options = PlanOptions.of(CATALOGUE, GROWTH, NOW.minus(1, ChronoUnit.DAYS), NOW);

    assertEquals(List.of(PlanAction.BUY, PlanAction.CURRENT, PlanAction.UPGRADE),
        options.stream().map(PlanOptionResponse::getAction).toList());
    assertTrue(options.stream().allMatch(PlanOptionResponse::isActionAvailable));
    assertTrue(options.stream().allMatch(o -> o.getAvailableFrom() == null));
  }

  @Test
  void withoutAPaidPlanEveryPlanIsAnAvailableBuy() {
    List<PlanOptionResponse> options = PlanOptions.of(CATALOGUE, null, NOW.plus(2, ChronoUnit.DAYS), NOW);

    assertTrue(options.stream().allMatch(o -> o.getAction() == PlanAction.BUY && o.isActionAvailable()));
  }

  @Test
  void aLegacyDuplicateOfACataloguePlanIsTheCurrentPlan() {
    Plan legacyGrowth = plan("old", "GROWTH", "5999");

    List<PlanOptionResponse> options = PlanOptions.of(CATALOGUE, legacyGrowth, NOW.plus(5, ChronoUnit.DAYS), NOW);

    assertEquals(PlanAction.CURRENT, options.get(1).getAction());
  }

  @Test
  void aLegacyPlanOutsideTheCatalogueIsRankedByYearlyPrice() {
    Plan legacy = plan("legacy", null, "6000");

    List<PlanOptionResponse> options = PlanOptions.of(CATALOGUE, legacy, NOW.minus(1, ChronoUnit.DAYS), NOW);

    assertEquals(List.of(PlanAction.BUY, PlanAction.BUY, PlanAction.UPGRADE),
        options.stream().map(PlanOptionResponse::getAction).toList());
  }

  private static Plan plan(String id, String code, String arcPrice) {
    Plan plan = new Plan();
    plan.setId(id);
    plan.setCode(code);
    plan.setArcPrice(new BigDecimal(arcPrice));
    return plan;
  }
}
