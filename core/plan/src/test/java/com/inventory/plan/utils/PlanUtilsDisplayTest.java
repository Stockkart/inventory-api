package com.inventory.plan.utils;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventory.plan.domain.model.Plan;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PlanUtilsDisplayTest {

  private static Plan plan(String name, String billingLimit, boolean unlimited) {
    Plan plan = new Plan();
    plan.setPlanName(name);
    plan.setBillingLimit(billingLimit == null ? null : new BigDecimal(billingLimit));
    plan.setUnlimited(unlimited);
    return plan;
  }

  @Test
  void legacyAddOnRowsAreNotSelectable() {
    assertThat(PlanUtils.isSelectable(plan("Extra User Plan", null, false))).isFalse();
    assertThat(PlanUtils.isSelectable(plan(" extra shop plan ", null, false))).isFalse();
    assertThat(PlanUtils.isSelectable(plan("Professional", null, false))).isTrue();
  }

  @Test
  void billingLimitIsShownInLakh() {
    assertThat(PlanUtils.billingLimitLabel(plan("Starter", "150000", false))).isEqualTo("₹1.5L");
    assertThat(PlanUtils.billingLimitLabel(plan("Starter", "1000000", false))).isEqualTo("₹10.0L");
    assertThat(PlanUtils.billingLimitLabel(plan("Ent", "150000", true))).isEqualTo("Unlimited");
    assertThat(PlanUtils.billingLimitLabel(plan("Ent", null, false))).isEqualTo("Unlimited");
  }

  @Test
  void thePlanResponseCarriesBoth() {
    var response = new com.inventory.plan.mapper.PlanMapperImpl().toResponse(plan("Extra User Plan", "200000", false));

    assertThat(response.isSelectable()).isFalse();
    assertThat(response.getBillingLimitLabel()).isEqualTo("\u20b92.0L");
  }
}
