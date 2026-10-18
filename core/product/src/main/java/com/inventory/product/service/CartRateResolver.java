package com.inventory.product.service;

import com.inventory.common.exception.ValidationException;
import com.inventory.pricing.rest.dto.response.RateDto;
import com.inventory.pricing.rest.dto.response.SchemeDto;
import com.inventory.pricing.utils.constants.PricingConstants;
import com.inventory.product.domain.model.Inventory;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
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

  /**
   * The terms for {@code rateName} on this batch. With no name, the batch's default rate is used
   * only when it is a rule rate; a legacy or system default (priceToRetail …) is already the
   * batch's selling price and changes nothing.
   *
   * @throws ValidationException when {@code rateName} is set and the batch has no rate by that name
   */
  public Optional<LineTerms> resolve(Inventory inventory, String rateName) {
    if (StringUtils.hasText(rateName)) {
      RateDto rate = find(inventory, rateName.trim())
          .orElseThrow(() -> new ValidationException("Unknown rate: " + rateName.trim()));
      return Optional.of(toTerms(rate));
    }
    if (inventory == null || !StringUtils.hasText(inventory.getDefaultRate())) {
      return Optional.empty();
    }
    return find(inventory, inventory.getDefaultRate().trim())
        .filter(rate -> rate.getBaseValue() != null)
        .map(CartRateResolver::toTerms);
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

  private static LineTerms toTerms(RateDto rate) {
    if (rate.getBaseValue() == null) {
      return new LineTerms(rate.getPrice(), null, null, null, null, null);
    }
    BigDecimal disc = rate.getDiscountPercent() != null ? rate.getDiscountPercent() : BigDecimal.ZERO;
    SchemeDto scheme = rate.getScheme();
    String type = scheme != null ? scheme.getSchemeType() : null;
    if (PricingConstants.SCHEME_TYPE_PERCENTAGE.equals(type)) {
      return new LineTerms(rate.getBaseValue(), disc, type, null, null, scheme.getSchemePercentage());
    }
    if (PricingConstants.SCHEME_TYPE_FIXED_UNITS.equals(type)) {
      return new LineTerms(rate.getBaseValue(), disc, type, scheme.getSchemePayFor(),
          scheme.getSchemeFree(), null);
    }
    return new LineTerms(rate.getBaseValue(), disc, null, null, null, null);
  }

  /**
   * Price, disc and scheme for a cart line. {@code discountPercent} is set (0 when the rule has
   * none) only for a rule rate; the scheme fields are null when it has no scheme.
   */
  public record LineTerms(BigDecimal price, BigDecimal discountPercent, String schemeType,
      Integer payFor, Integer free, BigDecimal percentage) {

    /** True for a rule rate: its disc and scheme replace the line's, including clearing a scheme. */
    public boolean replacesTerms() {
      return discountPercent != null;
    }
  }
}
