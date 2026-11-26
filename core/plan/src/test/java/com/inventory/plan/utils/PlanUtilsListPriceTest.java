package com.inventory.plan.utils;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventory.plan.domain.model.Plan;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PlanUtilsListPriceTest {

  @Test
  void catalogueAnchorIsArcPlusMarkup() {
    Plan plan = new Plan();
    plan.setCode("PROFESSIONAL");
    plan.setArcPrice(new BigDecimal("9999"));

    assertThat(PlanUtils.listPrice(plan)).isEqualByComparingTo("12999");
  }

  @Test
  void legacyPlanHasNoAnchor() {
    Plan plan = new Plan();
    plan.setArcPrice(new BigDecimal("499"));

    assertThat(PlanUtils.listPrice(plan)).isNull();
  }

  @Test
  void noAnchorWithoutArcPrice() {
    Plan plan = new Plan();
    plan.setCode("ENTERPRISE");

    assertThat(PlanUtils.listPrice(plan)).isNull();
  }
}
