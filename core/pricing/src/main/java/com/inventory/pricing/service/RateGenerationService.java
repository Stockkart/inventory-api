package com.inventory.pricing.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.util.GstMath;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.domain.model.RateGenerationResult;
import com.inventory.pricing.domain.model.RateRuleSpec;
import com.inventory.pricing.domain.model.Scheme;
import com.inventory.pricing.domain.model.SkippedRateRule;
import com.inventory.pricing.utils.PricingUtils;
import com.inventory.pricing.utils.RateRuleCalculator;
import com.inventory.pricing.utils.constants.PricingConstants;
import com.inventory.pricing.utils.constants.PricingMetricsConstants;
import com.inventory.pricing.validation.PricingValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Generates a batch's named rates from the shop's rate rules and merges them with the rates sent
 * and stored, by exact name.
 *
 * <ul>
 *   <li>A rule rate whose disc % or scheme is sent and differs from the stored one is an override:
 *       it needs the grant, keeps its new terms, is {@code manual} and is priced from them. A term
 *       that is null means "unchanged". A price sent for a rule rate is ignored.</li>
 *   <li>A stored {@code manual} rule rate is repriced from its own terms; below cost it is kept and
 *       reported.</li>
 *   <li>{@code resetRates} names need the grant and are regenerated from the rule.</li>
 *   <li>Other rule rates are regenerated; a rule skipped below cost leaves no rate.</li>
 *   <li>Rates matching no rule are kept as sent and marked {@code manual}; no grant needed.</li>
 * </ul>
 *
 * <p>A pricing with no id is a new batch: incoming rule rates are replaced by generated ones and no
 * grant is checked. A shop without rules (or no {@link ShopRateRulesPort}) is left untouched.
 */
@Slf4j
@Service
public class RateGenerationService {

  @Autowired(required = false)
  private ShopRateRulesPort shopRateRulesPort;

  @Autowired
  private MetricsWrapper metrics;

  @Autowired
  private PricingValidator pricingValidator;

  /** True when the shop has at least one rate rule. */
  public boolean hasRules(String shopId) {
    return !rulesFor(shopId).isEmpty();
  }

  /**
   * Mutates {@code pricing.rates}, {@code defaultRate} and {@code sellingPrice}.
   *
   * @param pricing the record with its new bases applied and its stored rates (none for a new batch)
   * @param incoming the rates sent, or null when the request sent none
   * @return the rules skipped below cost and the kept overrides that are below cost
   */
  public List<SkippedRateRule> apply(Pricing pricing, List<Rate> incoming, List<String> resetRates,
      String userId) {
    List<RateRuleSpec> rules = rulesFor(pricing.getShopId());
    if (rules.isEmpty()) {
      return List.of();
    }
    boolean create = pricing.getId() == null;
    Map<String, Rate> stored = create ? Map.of() : byName(pricing.getRates());
    Map<String, Rate> sent = byName(incoming);
    Set<String> resets = create || resetRates == null ? Set.of() : new HashSet<>(resetRates);
    Map<String, RateRuleSpec> ruleByName = new LinkedHashMap<>();
    rules.forEach(r -> ruleByName.put(r.name(), r));

    Map<String, Rate> overrides = create ? Map.of() : overrides(ruleByName, stored, sent);
    if (!overrides.isEmpty() || resets.stream().anyMatch(ruleByName::containsKey)) {
      requireGrant(userId, pricing.getShopId());
    }

    BigDecimal cost = PricingUtils.computeEffectiveCostPrice(pricing);
    BigDecimal gst = GstMath.parseGstRate(pricing.getSgst()).add(GstMath.parseGstRate(pricing.getCgst()));
    RateGenerationResult generated = RateRuleCalculator.generate(rules, pricing.getSellingPrice(),
        pricing.getPriceToRetail(), pricing.getMaximumRetailPrice(), cost, gst);
    Map<String, Rate> generatedByName = byName(generated.rates());

    List<SkippedRateRule> warnings = new ArrayList<>();
    List<Rate> result = new ArrayList<>();
    for (RateRuleSpec rule : rules) {
      String name = rule.name();
      Rate own = overrides.get(name);
      if (own == null && !resets.contains(name) && stored.containsKey(name)
          && Boolean.TRUE.equals(stored.get(name).getManual())) {
        own = stored.get(name);
      }
      if (own != null) {
        Rate repriced = repriceWithOwnTerms(rule, own, pricing, cost, gst, warnings);
        if (repriced != null) {
          result.add(repriced);
        }
      } else if (generatedByName.containsKey(name)) {
        result.add(generatedByName.get(name));
      }
    }
    for (SkippedRateRule skipped : generated.skipped()) {
      Rate kept = find(result, skipped.name());
      if (kept == null) {
        warnings.add(skipped);
        metrics.increment(PricingMetricsConstants.RATE_RULE_SKIPPED_TOTAL, 1,
            "module", PricingMetricsConstants.MODULE, "reason", skipped.reason());
      }
    }
    Map<String, Rate> legacySource = incoming != null ? sent : stored;
    for (Rate legacy : legacySource.values()) {
      if (!ruleByName.containsKey(legacy.getName())) {
        result.add(asManual(legacy));
      }
    }

    pricing.setRates(result);
    resolveDefaultRate(pricing, result);
    return warnings;
  }

  private List<RateRuleSpec> rulesFor(String shopId) {
    if (shopRateRulesPort == null || !StringUtils.hasText(shopId)) {
      return List.of();
    }
    List<RateRuleSpec> rules = shopRateRulesPort.rulesFor(shopId);
    return rules == null ? List.of() : rules;
  }

  /**
   * Rule rates sent with a disc % or scheme that differs from what is stored (or, with nothing
   * stored, from the rule). Each is returned with its merged terms: sent where sent, else stored.
   */
  private Map<String, Rate> overrides(Map<String, RateRuleSpec> ruleByName, Map<String, Rate> stored,
      Map<String, Rate> sent) {
    Map<String, Rate> overrides = new HashMap<>();
    for (Rate in : sent.values()) {
      RateRuleSpec rule = ruleByName.get(in.getName());
      if (rule == null) {
        continue;
      }
      Rate current = stored.get(in.getName());
      BigDecimal currentDisc = current != null ? current.getDiscountPercent() : rule.discountPercent();
      Scheme currentScheme = current != null ? current.getScheme() : rule.scheme();
      boolean discChanged = in.getDiscountPercent() != null && !sameAmount(in.getDiscountPercent(), currentDisc);
      boolean schemeChanged = in.getScheme() != null && !sameScheme(in.getScheme(), currentScheme);
      if (!discChanged && !schemeChanged) {
        continue;
      }
      BigDecimal disc = discChanged ? in.getDiscountPercent() : currentDisc;
      Scheme scheme = schemeChanged ? in.getScheme() : currentScheme;
      pricingValidator.validateRateTerms(in.getName(), disc, scheme);
      overrides.put(in.getName(), Rate.builder()
          .name(in.getName())
          .base(current != null && current.getBase() != null ? current.getBase() : rule.base())
          .discountPercent(disc)
          .scheme(scheme)
          .manual(true)
          .build());
    }
    return overrides;
  }

  /**
   * Prices a rate from its own base and terms. The calculator runs without a cost so the rate is
   * never dropped; a price below cost is reported instead. A rate whose base has no value is kept
   * as it was stored.
   */
  private Rate repriceWithOwnTerms(RateRuleSpec rule, Rate own, Pricing pricing, BigDecimal cost,
      BigDecimal gst, List<SkippedRateRule> warnings) {
    String base = own.getBase() != null ? own.getBase() : rule.base();
    RateRuleSpec spec = new RateRuleSpec(rule.name(), base, own.getDiscountPercent(), own.getScheme());
    List<Rate> priced = RateRuleCalculator.generate(List.of(spec), pricing.getSellingPrice(),
        pricing.getPriceToRetail(), pricing.getMaximumRetailPrice(), null, gst).rates();
    if (priced.isEmpty()) {
      return own.getPrice() != null ? own : null;
    }
    Rate rate = priced.get(0);
    rate.setManual(true);
    boolean inclusive = PricingConstants.GST_MODE_INCLUSIVE.equals(rate.getGstMode());
    if (RateRuleCalculator.isBelowCost(rate.getPrice(), inclusive, cost, gst)) {
      warnings.add(new SkippedRateRule(rate.getName(), RateRuleCalculator.REASON_BELOW_COST));
    }
    return rate;
  }

  /**
   * A defaultRate naming a rate that is no longer present falls back to priceToRetail (retailers
   * too), never throwing. A named default then sets the selling price to that rate's price; the
   * built-in defaults keep the selling price as it was, so a typed retailer selling price stays.
   */
  private void resolveDefaultRate(Pricing pricing, List<Rate> rates) {
    String defaultRate = pricing.getDefaultRate();
    if (!StringUtils.hasText(defaultRate) || isBuiltInDefault(defaultRate)) {
      return;
    }
    if (find(rates, defaultRate) == null) {
      log.warn("Default rate '{}' on pricing {} is no longer generated; falling back to {}",
          defaultRate, pricing.getId(), PricingConstants.DEFAULT_RATE_PRICE_TO_RETAIL);
      metrics.increment(PricingMetricsConstants.RATE_DEFAULT_FALLBACK_TOTAL, 1,
          "module", PricingMetricsConstants.MODULE);
      pricing.setDefaultRate(PricingConstants.DEFAULT_RATE_PRICE_TO_RETAIL);
    }
    pricing.setSellingPrice(PricingUtils.resolveEffectiveSellingPrice(pricing));
  }

  private void requireGrant(String userId, String shopId) {
    if (!shopRateRulesPort.canEditRates(userId, shopId)) {
      throw new BaseException(ErrorCode.ACCESS_DENIED, "You do not have permission to edit rates");
    }
  }

  private static boolean isBuiltInDefault(String defaultRate) {
    String dr = defaultRate.trim();
    return PricingConstants.DEFAULT_RATE_PRICE_TO_RETAIL.equalsIgnoreCase(dr)
        || PricingConstants.DEFAULT_RATE_MAXIMUM_RETAIL_PRICE.equalsIgnoreCase(dr)
        || PricingConstants.DEFAULT_RATE_COST_PRICE.equalsIgnoreCase(dr);
  }

  private static Rate asManual(Rate rate) {
    return Rate.builder()
        .name(rate.getName())
        .price(rate.getPrice())
        .base(rate.getBase())
        .baseValue(rate.getBaseValue())
        .discountPercent(rate.getDiscountPercent())
        .scheme(rate.getScheme())
        .manual(true)
        .gstMode(rate.getGstMode())
        .build();
  }

  private static Map<String, Rate> byName(List<Rate> rates) {
    Map<String, Rate> map = new LinkedHashMap<>();
    if (rates != null) {
      for (Rate r : rates) {
        if (r != null && r.getName() != null) {
          map.putIfAbsent(r.getName(), r);
        }
      }
    }
    return map;
  }

  private static Rate find(List<Rate> rates, String name) {
    return rates.stream().filter(r -> name.equals(r.getName())).findFirst().orElse(null);
  }

  private static boolean sameAmount(BigDecimal a, BigDecimal b) {
    if (a == null || b == null) {
      return a == b;
    }
    return a.compareTo(b) == 0;
  }

  private static boolean sameScheme(Scheme a, Scheme b) {
    if (a == null || b == null) {
      return a == b;
    }
    return Objects.equals(a.getSchemeType(), b.getSchemeType())
        && Objects.equals(a.getSchemePayFor(), b.getSchemePayFor())
        && Objects.equals(a.getSchemeFree(), b.getSchemeFree())
        && sameAmount(a.getSchemePercentage(), b.getSchemePercentage());
  }
}
