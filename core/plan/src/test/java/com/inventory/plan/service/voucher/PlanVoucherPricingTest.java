package com.inventory.plan.service.voucher;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.VoucherType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PlanVoucherPricingTest {

  @Test
  void roomAndMaxima() {
    Plan plan = starter("7999", "4999");

    assertThat(PlanVoucherPricing.room(plan)).isEqualByComparingTo("3000.00");
    assertThat(PlanVoucherPricing.maxPercent(plan)).isEqualByComparingTo("37.50");
  }

  @Test
  void percentAtMaxStaysOnFloor() {
    Plan plan = starter("7999", "4999");
    BigDecimal max = PlanVoucherPricing.maxPercent(plan);

    PlanVoucherCheck check = PlanVoucherPricing.check(VoucherType.PERCENT_OFF, max, plan);

    assertThat(check.priceAfter()).isGreaterThanOrEqualTo(new BigDecimal("4999"));
    assertThat(check.allowed()).isTrue();
  }

  @Test
  void flatOverRoomIsNotAllowed() {
    Plan plan = starter("7999", "4999");

    PlanVoucherCheck check = PlanVoucherPricing.check(VoucherType.FLAT_OFF, new BigDecimal("4000"), plan);

    assertThat(check.allowed()).isFalse();
    assertThat(check.priceAfter()).isEqualByComparingTo("3999.00");
    assertThat(PlanVoucherPricing.belowFloorMessage(plan, check)).isEqualTo(
        "₹4,000 off takes Starter to ₹3,999, below its base price ₹4,999. Most you can give: ₹3,000 (37.50%).");
  }

  @Test
  void nullBaseUsesOneRupeeFloor() {
    Plan plan = starter("7999", null);

    assertThat(PlanVoucherPricing.floor(plan)).isEqualByComparingTo("1.00");
    assertThat(PlanVoucherPricing.room(plan)).isEqualByComparingTo("7998.00");
    assertThat(PlanVoucherPricing.check(VoucherType.PERCENT_OFF, new BigDecimal("100"), plan).allowed()).isFalse();
    assertThat(PlanVoucherPricing.check(VoucherType.FLAT_OFF, new BigDecimal("7998"), plan).allowed()).isTrue();
    PlanVoucherCheck over = PlanVoucherPricing.check(VoucherType.FLAT_OFF, new BigDecimal("7999"), plan);
    assertThat(PlanVoucherPricing.belowFloorMessage(plan, over)).contains("below the ₹1 minimum");
  }

  @Test
  void cappedDiscountNeverCrossesFloor() {
    assertThat(PlanVoucherPricing.cappedDiscount(VoucherType.FLAT_OFF, new BigDecimal("3000"), starter("5999", "4999")))
        .isEqualByComparingTo("1000.00");
    assertThat(PlanVoucherPricing.cappedDiscount(VoucherType.FLAT_OFF, new BigDecimal("3000"), starter("4999", "4999")))
        .isEqualByComparingTo("0");
  }

  @Test
  void planPriceFallsBackToPrice() {
    Plan plan = new Plan();
    plan.setPrice(new BigDecimal("100"));

    assertThat(PlanVoucherPricing.planPrice(plan)).isEqualByComparingTo("100");
  }

  @Test
  void messageUsesPaiseOnlyWhenNotWhole() {
    Plan plan = starter("250000", "200000");
    PlanVoucherCheck check = PlanVoucherPricing.check(VoucherType.FLAT_OFF, new BigDecimal("100000.50"), plan);

    assertThat(PlanVoucherPricing.belowFloorMessage(plan, check)).isEqualTo(
        "₹1,00,000.50 off takes Starter to ₹1,49,999.50, below its base price ₹2,00,000. Most you can give: ₹50,000 (20.00%).");
  }

  @Test
  void nonExactMaxPercentStaysAllowed() {
    Plan plan = starter("7777", "4999");

    assertThat(PlanVoucherPricing.maxPercent(plan)).isEqualByComparingTo("35.72");
    assertThat(PlanVoucherPricing.rawDiscount(VoucherType.PERCENT_OFF, new BigDecimal("35.72"), plan))
        .isEqualByComparingTo("2777.94");
    assertThat(PlanVoucherPricing.check(VoucherType.PERCENT_OFF, new BigDecimal("35.72"), plan).allowed()).isTrue();
  }

  @Test
  void extraDecimalsNeverLetVoucherCrossFloor() {
    Plan plan = starter("7999.999", "4999");

    assertThat(PlanVoucherPricing.room(plan)).isEqualByComparingTo("3000.99");
    assertThat(PlanVoucherPricing.floor(starter("7999", "4999.001"))).isEqualByComparingTo("4999.01");
  }

  private static Plan starter(String arcPrice, String basePrice) {
    Plan plan = new Plan();
    plan.setCode("STARTER");
    plan.setPlanName("Starter");
    plan.setArcPrice(new BigDecimal(arcPrice));
    plan.setBasePrice(basePrice == null ? null : new BigDecimal(basePrice));
    return plan;
  }
}
