package com.inventory.pricing.service.api;

import com.inventory.common.util.GstMath;
import com.inventory.pricing.api.BatchPrices;
import com.inventory.pricing.api.GeneratedRate;
import com.inventory.pricing.api.GeneratedRates;
import com.inventory.pricing.api.RateRuleEngine;
import com.inventory.pricing.api.SchemeTerms;
import com.inventory.pricing.api.ShopRateRule;
import com.inventory.pricing.api.SkippedRule;
import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.domain.model.RateGenerationResult;
import com.inventory.pricing.utils.PricingUtils;
import com.inventory.pricing.utils.RateRuleCalculator;
import com.inventory.pricing.utils.RateRuleTermsConverter;
import com.inventory.pricing.utils.RateTermsText;
import com.inventory.pricing.validation.PricingValidator;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;

/** {@link RateRuleEngine} over pricing's own calculator and validator. */
@Service
public class RateRuleEngineImpl implements RateRuleEngine {

  private final PricingValidator pricingValidator;

  public RateRuleEngineImpl(PricingValidator pricingValidator) {
    this.pricingValidator = pricingValidator;
  }

  @Override
  public GeneratedRates generate(List<ShopRateRule> rules, BatchPrices prices) {
    BigDecimal cost = PricingUtils.computeEffectiveCostPrice(prices.costPrice(),
        prices.purchaseAdditionalDiscount(), RateRuleTermsConverter.toScheme(prices.purchaseScheme()),
        prices.costPriceIncludesTax(), prices.sgst(), prices.cgst());
    BigDecimal gst = GstMath.parseGstRate(prices.sgst()).add(GstMath.parseGstRate(prices.cgst()));
    RateGenerationResult result = RateRuleCalculator.generate(
        rules.stream().map(RateRuleTermsConverter::toSpec).toList(),
        prices.sellingPrice(), prices.priceToRetail(), prices.maximumRetailPrice(), cost, gst);
    List<GeneratedRate> rates = result.rates().stream().map(RateRuleEngineImpl::toGenerated).toList();
    List<SkippedRule> skipped = result.skipped().stream()
        .map(s -> new SkippedRule(s.name(), s.reason()))
        .toList();
    return new GeneratedRates(rates, skipped);
  }

  @Override
  public void validateScheme(String ruleName, SchemeTerms scheme) {
    pricingValidator.validateRateTerms(ruleName, null, RateRuleTermsConverter.toScheme(scheme));
  }

  private static GeneratedRate toGenerated(Rate rate) {
    return new GeneratedRate(rate.getName(), rate.getBase(), rate.getBaseValue(),
        RateRuleCalculator.afterDiscount(rate.getBaseValue(), rate.getDiscountPercent()),
        rate.getPrice(), rate.getDiscountPercent(), RateRuleTermsConverter.toTerms(rate.getScheme()),
        rate.getGstMode(), RateTermsText.of(rate));
  }
}
