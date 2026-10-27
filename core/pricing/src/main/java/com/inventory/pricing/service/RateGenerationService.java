package com.inventory.pricing.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.common.util.GstMath;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.pricing.api.ShopRateRule;
import com.inventory.pricing.api.port.ShopRateRulesPort;
import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.domain.model.RateGenerationResult;
import com.inventory.pricing.domain.model.RateRuleSpec;
import com.inventory.pricing.domain.model.Scheme;
import com.inventory.pricing.domain.model.SkippedRateRule;
import com.inventory.pricing.utils.PricingUtils;
import com.inventory.pricing.utils.RateRuleCalculator;
import com.inventory.pricing.utils.RateRuleTermsConverter;
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
 *   <li>Rates matching no rule keep only their name and price and are marked {@code manual}; no
 *       grant needed.</li>
 * </ul>
 *
 * <p>A pricing with no id is a new batch: incoming rule rates are replaced by generated ones and no
 * grant is checked. A shop without rules (or no {@link ShopRateRulesPort}) is left untouched.
 */
@Slf4j
@Service
public class RateGenerationService {

  /** Warning reason: a rule now names a rate that was typed by hand, and regenerated it. */
  public static final String REASON_REPLACED_BY_RULE = "REPLACED_BY_RULE";

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

  /** {@link #apply(Pricing, List, List, List, String)} with the shop's rules read here. */
  public List<SkippedRateRule> apply(Pricing pricing, List<Rate> incoming, List<String> resetRates,
      String userId) {
    return apply(pricing, rulesFor(pricing.getShopId()), incoming, resetRates, userId);
  }

  /**
   * Mutates {@code pricing.rates}, {@code defaultRate} and {@code sellingPrice}.
   *
   * @param pricing the record with its new bases applied and its stored rates (none for a new batch)
   * @param rules the shop's rules, read once by the caller ({@link #rulesFor}); empty does nothing
   * @param incoming the rates sent, or null when the request sent none
   * @return the rules skipped below cost, the kept overrides that are below cost, and the
   *     hand-typed rates a rule took over ({@link #REASON_REPLACED_BY_RULE})
   * @throws ValidationException when an override names a rate whose base the batch has no value
   *     for, or (on a saved batch) a rate sent differs from a rule's name only in case
   */
  public List<SkippedRateRule> apply(Pricing pricing, List<RateRuleSpec> rules, List<Rate> incoming,
      List<String> resetRates, String userId) {
    if (rules == null || rules.isEmpty()) {
      return List.of();
    }
    boolean create = pricing.getId() == null;
    Map<String, Rate> stored = create ? Map.of() : byName(pricing.getRates());
    Map<String, Rate> sent = byName(incoming);
    Set<String> resets = create || resetRates == null ? Set.of() : new HashSet<>(resetRates);
    Map<String, RateRuleSpec> ruleByName = new LinkedHashMap<>();
    rules.forEach(r -> ruleByName.put(r.name(), r));
    if (!create) {
      rejectCaseOnlyClashes(ruleByName, sent);
    }

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
      // A legacy hand-typed rate (no base) later covered by a rule is regenerated, not repriced.
      if (own == null && !resets.contains(name) && stored.containsKey(name)
          && Boolean.TRUE.equals(stored.get(name).getManual()) && stored.get(name).getBase() != null) {
        own = stored.get(name);
      }
      if (overrides.containsKey(name) && baseValueOf(own.getBase(), pricing) == null) {
        throw new ValidationException("Rate " + name + " is built on " + own.getBase()
            + ", which this batch has no value for, so its terms cannot be changed");
      }
      Rate storedRate = stored.get(name);
      if (own == null && storedRate != null && storedRate.getBase() == null
          && generatedByName.containsKey(name)) {
        // A rule now names a rate typed by hand: the rule's rate replaces it.
        warnings.add(new SkippedRateRule(name, REASON_REPLACED_BY_RULE));
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

  /** The shop's rate rules, in order; empty when it has none or no port is wired. */
  public List<RateRuleSpec> rulesFor(String shopId) {
    if (shopRateRulesPort == null || !StringUtils.hasText(shopId)) {
      return List.of();
    }
    List<ShopRateRule> rules = shopRateRulesPort.rulesFor(shopId);
    return rules == null ? List.of() : rules.stream().map(RateRuleTermsConverter::toSpec).toList();
  }

  /**
   * A rate sent whose name matches a rule's only when case is ignored ("rate-a" against "Rate-A")
   * would be kept as a second, hand-typed rate beside the rule's, two rates a cashier cannot tell
   * apart. Rates are matched by exact name, so it is rejected.
   */
  private static void rejectCaseOnlyClashes(Map<String, RateRuleSpec> ruleByName, Map<String, Rate> sent) {
    for (String name : sent.keySet()) {
      if (ruleByName.containsKey(name)) {
        continue;
      }
      for (String rule : ruleByName.keySet()) {
        if (rule.equalsIgnoreCase(name)) {
          throw new ValidationException("Rate " + name + " differs from rate rule " + rule
              + " only in case; use the rule's name " + rule);
        }
      }
    }
  }

  private static BigDecimal baseValueOf(String base, Pricing pricing) {
    if (PricingConstants.RATE_BASE_SELLING_PRICE.equals(base)) {
      return pricing.getSellingPrice();
    }
    if (PricingConstants.RATE_BASE_PRICE_TO_RETAIL.equals(base)) {
      return pricing.getPriceToRetail();
    }
    if (PricingConstants.RATE_BASE_MAXIMUM_RETAIL_PRICE.equals(base)) {
      return pricing.getMaximumRetailPrice();
    }
    return null;
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
      boolean discChanged = in.getDiscountPercent() != null && !sameDisc(in.getDiscountPercent(), currentDisc);
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
   * too), never throwing. So does one naming a rate built on the selling price, so the selling
   * price is never derived from a rate derived from it. A named default then sets the selling
   * price to that rate's price; the built-in defaults keep the selling price as it was, so a typed
   * retailer selling price stays.
   */
  private void resolveDefaultRate(Pricing pricing, List<Rate> rates) {
    String defaultRate = pricing.getDefaultRate();
    if (!StringUtils.hasText(defaultRate) || isBuiltInDefault(defaultRate)) {
      return;
    }
    Rate target = find(rates, defaultRate);
    boolean missing = target == null;
    if (missing || !PricingUtils.canBeDefaultRate(target)) {
      log.warn("Default rate '{}' on pricing {} is {}; falling back to {}", defaultRate, pricing.getId(),
          missing ? "no longer generated" : "built on the selling price",
          PricingConstants.DEFAULT_RATE_PRICE_TO_RETAIL);
      metrics.increment(PricingMetricsConstants.RATE_DEFAULT_FALLBACK_TOTAL, 1,
          "module", PricingMetricsConstants.MODULE);
      pricing.setDefaultRate(PricingConstants.DEFAULT_RATE_PRICE_TO_RETAIL);
    }
    pricing.setSellingPrice(PricingUtils.resolveEffectiveSellingPrice(pricing));
  }

  private void requireGrant(String userId, String shopId) {
    if (!shopRateRulesPort.canEditRates(shopId, userId)) {
      throw new BaseException(ErrorCode.ACCESS_DENIED, "You do not have permission to edit rates");
    }
  }

  private static boolean isBuiltInDefault(String defaultRate) {
    String dr = defaultRate.trim();
    return PricingConstants.DEFAULT_RATE_PRICE_TO_RETAIL.equalsIgnoreCase(dr)
        || PricingConstants.DEFAULT_RATE_MAXIMUM_RETAIL_PRICE.equalsIgnoreCase(dr)
        || PricingConstants.DEFAULT_RATE_COST_PRICE.equalsIgnoreCase(dr);
  }

  /**
   * A rate that matches no rule, as stored: its name and price, marked {@code manual}. Every other
   * field is dropped, because only a rule can produce them: a client-sent {@code baseValue} would
   * otherwise be what checkout bills ({@code CartRateResolver}).
   */
  static Rate asManual(Rate rate) {
    return Rate.builder()
        .name(rate.getName())
        .price(rate.getPrice())
        .manual(true)
        .build();
  }

  /**
   * True when a stored rate still carries rule terms (a base). On a shop with no rules left those
   * terms are stale: checkout would still bill them, so the next write strips them.
   */
  static boolean hasRuleTerms(List<Rate> rates) {
    return rates != null && rates.stream().anyMatch(r -> r != null && r.getBase() != null);
  }

  /** {@link #asManual} for each rate; the rates of a shop without rules are stored this way. */
  static List<Rate> asManualRates(List<Rate> rates) {
    if (rates == null) {
      return null;
    }
    List<Rate> result = new ArrayList<>(rates.size());
    for (Rate r : rates) {
      result.add(r == null ? null : asManual(r));
    }
    return result;
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

  /** Disc % null and 0 are the same: echoing 0 for a rule with no disc is not an override. */
  private static boolean sameDisc(BigDecimal a, BigDecimal b) {
    return (a == null ? BigDecimal.ZERO : a).compareTo(b == null ? BigDecimal.ZERO : b) == 0;
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
