package com.inventory.plan.utils;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventory.common.entitlement.PlanFeature;
import com.inventory.plan.domain.model.AddOn;
import com.inventory.plan.domain.model.AddOnGrantType;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.utils.constants.PricingConstants;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AddOnEligibilityTest {

  private static Plan plan(String code, boolean unlimited) {
    Plan plan = new Plan();
    plan.setCode(code);
    plan.setPlanName("Pro");
    plan.setUnlimited(unlimited);
    plan.setFeatures(Set.of(PlanFeature.ACCOUNTING));
    return plan;
  }

  private static AddOn addOn(AddOnGrantType type, PlanFeature feature, boolean stackable, boolean active) {
    return AddOn.builder().code("X").name("X").grantType(type).grantsFeature(feature)
        .stackable(stackable).active(active).build();
  }

  @Test
  void eachRuleGivesItsReason() {
    Plan pro = plan("PRO", false);
    assertThat(AddOnEligibility.ineligibleReason(pro, addOn(AddOnGrantType.FEATURE, PlanFeature.MARKETING, false, true), Set.of()))
        .isEmpty();
    assertThat(AddOnEligibility.ineligibleReason(pro, addOn(AddOnGrantType.FEATURE, PlanFeature.MARKETING, false, false), Set.of()))
        .contains("X is no longer sold");
    assertThat(AddOnEligibility.ineligibleReason(pro, addOn(AddOnGrantType.SMS, null, true, true), Set.of()))
        .contains(PricingConstants.MSG_SMS_COMING_SOON);
    assertThat(AddOnEligibility.ineligibleReason(plan(null, false), addOn(AddOnGrantType.SEATS, null, true, true), Set.of()))
        .contains(PricingConstants.MSG_LEGACY_PLAN_HAS_EVERYTHING);
    assertThat(AddOnEligibility.ineligibleReason(pro, addOn(AddOnGrantType.FEATURE, PlanFeature.ACCOUNTING, false, true), Set.of()))
        .contains("Pro already includes X");
    assertThat(AddOnEligibility.ineligibleReason(plan("PRO", true), addOn(AddOnGrantType.SEATS, null, true, true), Set.of()))
        .contains("Pro already has unlimited users");
    assertThat(AddOnEligibility.ineligibleReason(pro, addOn(AddOnGrantType.FEATURE, PlanFeature.MARKETING, false, true), Set.of("X")))
        .contains("You already have X");
  }

  @Test
  void stackableAddOnsAndOcrCreditsCanBeBoughtAgain() {
    assertThat(AddOnEligibility.isHeldOnce(addOn(AddOnGrantType.SEATS, null, true, true), Set.of("X"))).isFalse();
    assertThat(AddOnEligibility.isHeldOnce(addOn(AddOnGrantType.OCR_CREDITS, null, false, true), Set.of("X"))).isFalse();
  }
}
