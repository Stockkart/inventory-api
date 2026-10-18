package com.inventory.pricing.utils;

import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.domain.model.RateGenerationResult;
import com.inventory.pricing.domain.model.RateRuleSpec;
import com.inventory.pricing.domain.model.Scheme;
import com.inventory.pricing.domain.model.SkippedRateRule;
import com.inventory.pricing.utils.constants.PricingConstants;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns shop rate rules into a batch's named rates. Pure: no I/O, nothing thrown on a normal path.
 *
 * <p>Per-unit price = {@code baseValue × (1 − disc/100)}, then the scheme (PERCENTAGE cuts price,
 * FIXED_UNITS scales by payFor/(payFor+free)), rounded HALF_UP to 2 dp only at the end.
 */
@Slf4j
public final class RateRuleCalculator {

  /** Reason on a {@link SkippedRateRule} whose price, ex-GST, is under the batch cost. */
  public static final String REASON_BELOW_COST = "BELOW_COST";

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private RateRuleCalculator() {}

  /**
   * @param cost effective cost, falling back to cost price (caller resolves); null disables the check
   * @param gstPercent sgst + cgst
   */
  public static RateGenerationResult generate(List<RateRuleSpec> rules, BigDecimal sellingPrice,
      BigDecimal priceToRetail, BigDecimal mrp, BigDecimal cost, BigDecimal gstPercent) {
    List<Rate> rates = new ArrayList<>();
    List<SkippedRateRule> skipped = new ArrayList<>();
    if (rules == null) {
      return new RateGenerationResult(rates, skipped);
    }
    for (RateRuleSpec rule : rules) {
      BigDecimal baseValue = baseValue(rule.base(), sellingPrice, priceToRetail, mrp);
      if (baseValue == null) {
        log.info("Rate rule '{}' skipped: no value for base {}", rule.name(), rule.base());
        continue;
      }
      BigDecimal price = price(baseValue, rule.discountPercent(), rule.scheme());
      boolean inclusive = SchemePriceUtils.isAtMrp(baseValue, mrp);
      if (isBelowCost(price, inclusive, cost, gstPercent)) {
        skipped.add(new SkippedRateRule(rule.name(), REASON_BELOW_COST));
        continue;
      }
      rates.add(Rate.builder()
          .name(rule.name())
          .price(price)
          .base(rule.base())
          .baseValue(baseValue)
          .discountPercent(rule.discountPercent())
          .scheme(rule.scheme())
          .manual(false)
          .gstMode(inclusive ? PricingConstants.GST_MODE_INCLUSIVE : PricingConstants.GST_MODE_EXCLUSIVE)
          .build());
    }
    return new RateGenerationResult(rates, skipped);
  }

  private static BigDecimal baseValue(String base, BigDecimal sellingPrice, BigDecimal priceToRetail,
      BigDecimal mrp) {
    if (PricingConstants.RATE_BASE_SELLING_PRICE.equals(base)) {
      return sellingPrice;
    }
    if (PricingConstants.RATE_BASE_PRICE_TO_RETAIL.equals(base)) {
      return priceToRetail;
    }
    if (PricingConstants.RATE_BASE_MAXIMUM_RETAIL_PRICE.equals(base)) {
      return mrp;
    }
    return null;
  }

  private static BigDecimal price(BigDecimal baseValue, BigDecimal discountPercent, Scheme scheme) {
    BigDecimal afterDisc = baseValue;
    if (discountPercent != null && discountPercent.signum() > 0) {
      afterDisc = baseValue.multiply(BigDecimal.ONE.subtract(
          discountPercent.divide(HUNDRED, 4, RoundingMode.HALF_UP)));
    }
    String type = scheme == null ? null : scheme.getSchemeType();
    BigDecimal unitPrice = SchemePriceUtils.percentagePrice(afterDisc, type,
        scheme == null ? null : scheme.getSchemePercentage());
    BigDecimal billable = SchemePriceUtils.billableQuantity(BigDecimal.ONE, type,
        scheme == null ? null : scheme.getSchemePayFor(),
        scheme == null ? null : scheme.getSchemeFree());
    return unitPrice.multiply(billable).setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * True when a rate's price is under cost. Compares like with like: an inclusive price is divided
   * by (1 + GST%) at 4 dp before the check. A null cost disables the check.
   */
  public static boolean isBelowCost(BigDecimal price, boolean inclusive, BigDecimal cost,
      BigDecimal gstPercent) {
    if (cost == null) {
      return false;
    }
    BigDecimal comparable = price;
    if (inclusive && gstPercent != null && gstPercent.signum() > 0) {
      comparable = price.divide(
          BigDecimal.ONE.add(gstPercent.divide(HUNDRED, 4, RoundingMode.HALF_UP)), 4, RoundingMode.HALF_UP);
    }
    return comparable.compareTo(cost) < 0;
  }
}
