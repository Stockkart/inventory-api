package com.inventory.pricing.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.rest.dto.CreatePricingRequest;
import com.inventory.pricing.rest.dto.UpdateDefaultPriceItem;
import com.inventory.pricing.rest.dto.UpdateDefaultPriceRequest;
import com.inventory.pricing.rest.dto.UpdatePricingRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

@Component
public class PricingValidator {

  public void validateCreateRequest(CreatePricingRequest request) {
    if (request == null) {
      throw new ValidationException("Create pricing request cannot be null");
    }
    if (!StringUtils.hasText(request.getShopId())) {
      throw new ValidationException("Shop ID is required for pricing");
    }
    validateRates(request.getRates(), request.getDefaultRate());
    BigDecimal effectiveSp = resolveEffectivePrice(request.getPriceToRetail(), request.getRates(), request.getDefaultRate());
    if (effectiveSp == null) {
      throw new ValidationException("Either priceToRetail or (rates with defaultRate) is required");
    }
    validatePriceValues(request.getMaximumRetailPrice(), request.getCostPrice(),
        effectiveSp, request.getAdditionalDiscount());
    validateGstRates(request.getSgst(), request.getCgst());
  }

  public void validateUpdateRequest(UpdatePricingRequest request) {
    if (request == null) {
      return;
    }
    validateRates(request.getRates(), request.getDefaultRate());
    validateGstRates(request.getSgst(), request.getCgst());
    if (request.getMaximumRetailPrice() != null || request.getCostPrice() != null
        || request.getPriceToRetail() != null || request.getAdditionalDiscount() != null) {
      validatePriceValues(request.getMaximumRetailPrice(), request.getCostPrice(),
          request.getPriceToRetail(), request.getAdditionalDiscount());
    }
  }

  public void validatePricingId(String pricingId) {
    if (!StringUtils.hasText(pricingId)) {
      throw new ValidationException("Pricing ID is required");
    }
  }

  /**
   * Validate update default price request. At least one of maximumRetailPrice, priceToRetail,
   * rates, or defaultRate must be provided. Updates must be within rates array, priceToRetail, or MRP.
   */
  public void validateUpdateDefaultPriceRequest(UpdateDefaultPriceRequest request) {
    if (request == null) {
      throw new ValidationException("Update request cannot be null");
    }
    boolean hasAny = request.getMaximumRetailPrice() != null
        || request.getPriceToRetail() != null
        || (request.getRates() != null && !request.getRates().isEmpty())
        || StringUtils.hasText(request.getDefaultRate());
    if (!hasAny) {
      throw new ValidationException("At least one of maximumRetailPrice, priceToRetail, rates, or defaultRate must be provided");
    }
    validateRates(request.getRates(), request.getDefaultRate());
    if (request.getMaximumRetailPrice() != null && request.getMaximumRetailPrice().compareTo(BigDecimal.ZERO) < 0) {
      throw new ValidationException("Maximum retail price cannot be negative");
    }
    if (request.getPriceToRetail() != null && request.getPriceToRetail().compareTo(BigDecimal.ZERO) < 0) {
      throw new ValidationException("Price to retail cannot be negative");
    }
  }

  public void validateUpdateDefaultPriceItem(UpdateDefaultPriceItem item) {
    if (item == null) {
      throw new ValidationException("Update item cannot be null");
    }
    if (!StringUtils.hasText(item.getPricingId())) {
      throw new ValidationException("Pricing ID is required for each update item");
    }
    boolean hasAny = item.getMaximumRetailPrice() != null
        || item.getPriceToRetail() != null
        || (item.getRates() != null && !item.getRates().isEmpty())
        || StringUtils.hasText(item.getDefaultRate());
    if (!hasAny) {
      throw new ValidationException("At least one of maximumRetailPrice, priceToRetail, rates, or defaultRate must be provided");
    }
    validateRates(item.getRates(), item.getDefaultRate());
    if (item.getMaximumRetailPrice() != null && item.getMaximumRetailPrice().compareTo(BigDecimal.ZERO) < 0) {
      throw new ValidationException("Maximum retail price cannot be negative");
    }
    if (item.getPriceToRetail() != null && item.getPriceToRetail().compareTo(BigDecimal.ZERO) < 0) {
      throw new ValidationException("Price to retail cannot be negative");
    }
  }

  private void validatePriceValues(BigDecimal mrp, BigDecimal cp, BigDecimal sp, BigDecimal additionalDiscount) {
    if (mrp != null && mrp.compareTo(BigDecimal.ZERO) < 0) {
      throw new ValidationException("Maximum retail price cannot be negative");
    }
    if (cp != null && cp.compareTo(BigDecimal.ZERO) < 0) {
      throw new ValidationException("Cost price cannot be negative");
    }
    if (sp != null && sp.compareTo(BigDecimal.ZERO) < 0) {
      throw new ValidationException("Selling price cannot be negative");
    }
    if (additionalDiscount != null &&
        (additionalDiscount.compareTo(BigDecimal.ZERO) < 0 || additionalDiscount.compareTo(BigDecimal.valueOf(100)) > 0)) {
      throw new ValidationException("Additional discount must be between 0 and 100");
    }
  }

  private void validateRates(List<Rate> rates, String defaultRate) {
    if (rates == null || rates.isEmpty()) {
      return;
    }
    for (Rate r : rates) {
      if (!StringUtils.hasText(r.getName())) {
        throw new ValidationException("Rate name cannot be blank");
      }
      if (r.getPrice() == null) {
        throw new ValidationException("Rate price is required: " + r.getName());
      }
      if (r.getPrice().compareTo(BigDecimal.ZERO) < 0) {
        throw new ValidationException("Rate price cannot be negative: " + r.getName());
      }
    }
    if (StringUtils.hasText(defaultRate)) {
      boolean found = rates.stream().anyMatch(r -> defaultRate.equals(r.getName()));
      if (!found) {
        throw new ValidationException("defaultRate '" + defaultRate + "' must match a rate name in rates");
      }
    }
  }

  private static BigDecimal resolveEffectivePrice(BigDecimal priceToRetail, List<Rate> rates, String defaultRate) {
    if (StringUtils.hasText(defaultRate) && rates != null && !rates.isEmpty()) {
      return rates.stream()
          .filter(r -> defaultRate.equals(r.getName()))
          .map(Rate::getPrice)
          .findFirst()
          .orElse(priceToRetail);
    }
    return priceToRetail;
  }

  private void validateGstRates(String sgst, String cgst) {
    if (StringUtils.hasText(sgst)) {
      try {
        BigDecimal rate = new BigDecimal(sgst.trim());
        if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0) {
          throw new ValidationException("SGST rate must be between 0 and 100");
        }
      } catch (NumberFormatException e) {
        throw new ValidationException("Invalid SGST rate format");
      }
    }
    if (StringUtils.hasText(cgst)) {
      try {
        BigDecimal rate = new BigDecimal(cgst.trim());
        if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0) {
          throw new ValidationException("CGST rate must be between 0 and 100");
        }
      } catch (NumberFormatException e) {
        throw new ValidationException("Invalid CGST rate format");
      }
    }
  }
}
