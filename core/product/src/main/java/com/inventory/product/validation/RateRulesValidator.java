package com.inventory.product.validation;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.pricing.domain.model.Scheme;
import com.inventory.pricing.rest.dto.response.SchemeDto;
import com.inventory.pricing.utils.constants.PricingConstants;
import com.inventory.pricing.validation.PricingValidator;
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

  /** Names a rule may not take: they are the shop's built-in rate names. */
  static final Set<String> RESERVED_NAMES = Set.of(
      PricingConstants.DEFAULT_RATE_PRICE_TO_RETAIL.toLowerCase(Locale.ROOT),
      PricingConstants.DEFAULT_RATE_MAXIMUM_RETAIL_PRICE.toLowerCase(Locale.ROOT),
      PricingConstants.DEFAULT_RATE_COST_PRICE.toLowerCase(Locale.ROOT));

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final PricingValidator pricingValidator;

  public RateRulesValidator(PricingValidator pricingValidator) {
    this.pricingValidator = pricingValidator;
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
      if (RESERVED_NAMES.contains(key)) {
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
      SchemeDto scheme = rule.getScheme();
      if (disc == null && scheme == null) {
        throw invalid("Rate rule needs a discount, a scheme, or both: " + name);
      }
      if (disc != null && (disc.signum() < 0 || disc.compareTo(HUNDRED) > 0)) {
        throw invalid("Rate rule discount must be between 0 and 100: " + name);
      }
      if (scheme != null) {
        pricingValidator.validateRateTerms(name, null, toScheme(scheme));
      }
    }
  }

  private static Scheme toScheme(SchemeDto dto) {
    return new Scheme(dto.getSchemeType(), dto.getSchemePayFor(), dto.getSchemeFree(),
        dto.getSchemePercentage());
  }

  private static BaseException invalid(String message) {
    return new BaseException(ErrorCode.RATE_RULE_INVALID, message);
  }
}
