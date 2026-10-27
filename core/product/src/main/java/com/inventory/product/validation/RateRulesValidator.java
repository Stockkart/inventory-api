package com.inventory.product.validation;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.pricing.api.RateRuleEngine;
import com.inventory.pricing.api.SchemeTerms;
import com.inventory.pricing.api.SystemRates;
import com.inventory.product.domain.model.enums.RateBase;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.rest.dto.response.RateRuleDto;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Checks a shop's rate rule list before it is saved or previewed. */
@Component
public class RateRulesValidator {

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final RateRuleEngine rateRuleEngine;

  public RateRulesValidator(RateRuleEngine rateRuleEngine) {
    this.rateRuleEngine = rateRuleEngine;
  }

  /** The bases a shop type may use, in display order. */
  public static List<String> baseOptions(ShopType shopType) {
    if (shopType == ShopType.RETAILER) {
      return List.of(RateBase.SELLING_PRICE.value());
    }
    return List.of(RateBase.PRICE_TO_RETAIL.value(), RateBase.MAXIMUM_RETAIL_PRICE.value());
  }

  /** Throws a {@link BaseException} with {@code RATE_RULE_INVALID} or {@code RATE_RULE_BASE_NOT_ALLOWED}. */
  public void validate(List<RateRuleDto> rules, ShopType shopType) {
    if (rules == null) {
      return;
    }
    List<String> allowedBases = baseOptions(shopType);
    Set<String> seen = new HashSet<>();
    for (RateRuleDto rule : rules) {
      if (rule == null) {
        throw invalid("A rate rule cannot be empty");
      }
      String name = rule.getName() == null ? null : rule.getName().trim();
      if (!StringUtils.hasText(name)) {
        throw invalid("Rate rule name is required");
      }
      String key = name.toLowerCase(Locale.ROOT);
      // The built-in prices' keys and counter labels (priceToRetail, PTR, MRP, Cost …).
      if (SystemRates.isReserved(name)) {
        throw invalid("Rate rule name is reserved: " + name);
      }
      if (!seen.add(key)) {
        throw invalid("Rate rule name is used twice: " + name);
      }
      if (RateBase.fromValue(rule.getBase()) == null) {
        throw invalid("Rate rule base is not recognised: " + name);
      }
      if (!allowedBases.contains(rule.getBase())) {
        throw new BaseException(ErrorCode.RATE_RULE_BASE_NOT_ALLOWED,
            "Rate rule base " + rule.getBase() + " is not allowed for this shop type: " + name);
      }
      BigDecimal disc = rule.getDiscountPercent();
      SchemeTerms scheme = rule.getScheme();
      // A rule with no disc (or 0) and no scheme is its base unchanged: not a rate of its own.
      if ((disc == null || disc.signum() == 0) && scheme == null) {
        throw invalid("Rate rule needs a discount above 0, a scheme, or both: " + name);
      }
      if (disc != null && (disc.signum() < 0 || disc.compareTo(HUNDRED) > 0)) {
        throw invalid("Rate rule discount must be between 0 and 100: " + name);
      }
      if (scheme != null) {
        rateRuleEngine.validateScheme(name, scheme);
      }
    }
  }

  private static BaseException invalid(String message) {
    return new BaseException(ErrorCode.RATE_RULE_INVALID, message);
  }
}
