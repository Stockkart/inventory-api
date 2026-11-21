package com.inventory.plan.utils;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventory.plan.utils.PlanGstUtils.GstSplit;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PlanGstUtilsTest {

  @Test
  void aTaxInclusiveTotalSplitsIntoTaxableAndGstThatAddUpExactly() {
    GstSplit split = PlanGstUtils.splitInclusive(new BigDecimal("9999"), new BigDecimal("18"));

    assertThat(split.taxable()).isEqualByComparingTo("8473.73");
    assertThat(split.gst()).isEqualByComparingTo("1525.27");
    assertThat(split.taxable().add(split.gst())).isEqualByComparingTo("9999.00");
  }

  @Test
  void roundingNeverLosesAPaisa() {
    for (int paise = 1; paise <= 5000; paise += 7) {
      BigDecimal total = BigDecimal.valueOf(paise, 2);
      GstSplit split = PlanGstUtils.splitInclusive(total, new BigDecimal("18"));
      assertThat(split.taxable().add(split.gst())).isEqualByComparingTo(total);
    }
  }

  @Test
  void zeroOrMissingTotalsHaveNoTax() {
    assertThat(PlanGstUtils.splitInclusive(null, new BigDecimal("18"))).isEqualTo(GstSplit.ZERO);
    assertThat(PlanGstUtils.splitInclusive(BigDecimal.ZERO, new BigDecimal("18")).gst()).isEqualByComparingTo("0");
  }

  @Test
  void zeroRateIsAllTaxable() {
    GstSplit split = PlanGstUtils.splitInclusive(new BigDecimal("100"), BigDecimal.ZERO);
    assertThat(split.taxable()).isEqualByComparingTo("100");
    assertThat(split.gst()).isEqualByComparingTo("0");
  }
}
