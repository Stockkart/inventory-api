package com.inventory.product.service;

import com.inventory.common.exception.ValidationException;
import com.inventory.pricing.domain.model.RateRuleSpec;
import com.inventory.pricing.rest.dto.response.RateDto;
import com.inventory.pricing.rest.dto.response.SchemeDto;
import com.inventory.pricing.utils.SystemRateNames;
import com.inventory.pricing.service.ShopRateRulesPort;
import com.inventory.pricing.utils.constants.PricingConstants;
import com.inventory.product.domain.model.Inventory;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Which price, disc and scheme a cart line takes from a batch rate.
 *
 * <p>A rule rate (one with a {@code baseValue}) puts its base on the line with its own disc and
 * scheme, so checkout's arithmetic applies them once: Rate-A (PTR 100 − 8% · 10+1) goes on as
 * 100, 8%, 10+1 and bills 920.00 for 11, never 83.64 with the terms on top. A legacy rate (name
 * and price only) gives its price and leaves the line's terms as checkout sets them today.
 */
@Component
public class CartRateResolver {

  @Autowired(required = false)
  private ShopRateRulesPort shopRateRulesPort;

  /**
   * The terms for {@code rateName} on this batch. With no name, the batch's default rate is used
   * only when it is a rule rate; a legacy or system default (priceToRetail …) is already the
   * batch's selling price and changes nothing.
   *
   * <p>A system name ({@link SystemRateNames}: {@code priceToRetail}, {@code MRP} …) means the
   * batch's own field, never a stored rate of the same name; see {@link LineTerms#systemKey()}.
   *
   * @throws ValidationException when {@code rateName} is set and the batch has no rate by that
   *     name, or names a system field the batch has no value for
   */
  public Optional<LineTerms> resolve(Inventory inventory, String rateName) {
    if (StringUtils.hasText(rateName)) {
      String systemKey = SystemRateNames.systemKey(rateName);
      if (systemKey != null) {
        return Optional.of(systemTerms(inventory, systemKey));
      }
      RateDto rate = find(inventory, rateName.trim())
          .orElseThrow(() -> new ValidationException("Unknown rate: " + rateName.trim()));
      return Optional.of(isLiveRuleRate(inventory, rate) ? toTerms(rate) : legacyTerms(rate));
    }
    if (inventory == null || !StringUtils.hasText(inventory.getDefaultRate())) {
      return Optional.empty();
    }
    return find(inventory, inventory.getDefaultRate().trim())
        .filter(rate -> isLiveRuleRate(inventory, rate))
        .map(CartRateResolver::toTerms);
  }

  /**
   * Whether a stored rate bills as a rule rate: it has a base, and its rule is still among the
   * shop's rules. A rate whose rule was removed (or the shop's rules cleared) keeps its stored
   * terms until the batch is next saved; here it bills its price only, as a legacy rate, and is
   * no default rule rate. Without a {@link ShopRateRulesPort} the rules are not checked.
   */
  private boolean isLiveRuleRate(Inventory inventory, RateDto rate) {
    if (rate.getBaseValue() == null) {
      return false;
    }
    if (shopRateRulesPort == null) {
      return true;
    }
    List<RateRuleSpec> rules = shopRateRulesPort.rulesFor(inventory.getShopId());
    return rules != null && rules.stream().anyMatch(r -> rate.getName().equals(r.name()));
  }

  private static LineTerms legacyTerms(RateDto rate) {
    return new LineTerms(rate.getName(), null, rate.getPrice(), null, null, null, null, null);
  }

  private static Optional<RateDto> find(Inventory inventory, String name) {
    List<RateDto> rates = inventory != null ? inventory.getRates() : null;
    if (rates == null) {
      return Optional.empty();
    }
    return rates.stream()
        .filter(rate -> rate != null && name.equals(rate.getName()))
        .findFirst();
  }

  private static LineTerms systemTerms(Inventory inventory, String systemKey) {
    BigDecimal price = null;
    if (inventory != null) {
      if (PricingConstants.DEFAULT_RATE_PRICE_TO_RETAIL.equals(systemKey)) {
        price = inventory.getPriceToRetail();
      } else if (PricingConstants.DEFAULT_RATE_MAXIMUM_RETAIL_PRICE.equals(systemKey)) {
        price = inventory.getMaximumRetailPrice();
      } else if (PricingConstants.DEFAULT_RATE_COST_PRICE.equals(systemKey)) {
        price = inventory.getCostPrice();
      }
    }
    if (price == null) {
      throw new ValidationException("This batch has no " + systemKey + " to bill at");
    }
    return new LineTerms(systemKey, systemKey, price, null, null, null, null, null);
  }

  private static LineTerms toTerms(RateDto rate) {
    String name = rate.getName();
    if (rate.getBaseValue() == null) {
      return legacyTerms(rate);
    }
    BigDecimal disc = rate.getDiscountPercent() != null ? rate.getDiscountPercent() : BigDecimal.ZERO;
    SchemeDto scheme = rate.getScheme();
    String type = scheme != null ? scheme.getSchemeType() : null;
    if (PricingConstants.SCHEME_TYPE_PERCENTAGE.equals(type)) {
      return new LineTerms(name, null, rate.getBaseValue(), disc, type, null, null,
          scheme.getSchemePercentage());
    }
    if (PricingConstants.SCHEME_TYPE_FIXED_UNITS.equals(type)) {
      return new LineTerms(name, null, rate.getBaseValue(), disc, type, scheme.getSchemePayFor(),
          scheme.getSchemeFree(), null);
    }
    return new LineTerms(name, null, rate.getBaseValue(), disc, null, null, null, null);
  }

  /**
   * Price, disc and scheme for a cart line, per pack (the batch's pricing unit). {@code
   * discountPercent} is set (0 when the rule has none) only for a rule rate; the scheme fields
   * are null when it has no scheme. {@code name} is the rate name the line records as applied;
   * {@code systemKey} is set when the pick is a system field, whose disc and scheme are the
   * batch's own sale defaults.
   */
  public record LineTerms(String name, String systemKey, BigDecimal price,
      BigDecimal discountPercent, String schemeType, Integer payFor, Integer free,
      BigDecimal percentage) {

    /** True for a rule rate: its disc and scheme replace the line's, including clearing a scheme. */
    public boolean replacesTerms() {
      return discountPercent != null;
    }

    /** True for a system pick (PTR, MRP, cost). */
    public boolean isSystem() {
      return systemKey != null;
    }
  }
}
