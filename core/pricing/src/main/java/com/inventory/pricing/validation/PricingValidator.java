package com.inventory.pricing.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.rest.dto.request.CreatePricingRequest;
import com.inventory.pricing.rest.dto.request.PricingCreateCommand;
import com.inventory.pricing.rest.dto.request.PricingUpdateCommand;
import com.inventory.pricing.rest.dto.request.UpdateDefaultPriceItem;
import com.inventory.pricing.rest.dto.request.UpdateDefaultPriceRequest;
import com.inventory.pricing.rest.dto.request.UpdatePricingRequest;
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
    BigDecimal effectiveSp = resolveEffectivePrice(request.getMaximumRetailPrice(), request.getCostPrice(),
        request.getPriceToRetail(), request.getRates(), request.getDefaultRate());
    if (effectiveSp == null) {
      throw new ValidationException("Either priceToRetail or (rates with defaultRate) is required");
    }
    validatePriceValues(request.getMaximumRetailPrice(), request.getCostPrice(),
        effectiveSp, request.getSaleAdditionalDiscount());
    validateGstRates(request.getSgst(), request.getCgst());
  }

  public void validateUpdateRequest(UpdatePricingRequest request) {
    if (request == null) {
      return;
    }
    validateRates(request.getRates(), request.getDefaultRate());
    validateGstRates(request.getSgst(), request.getCgst());
    if (request.getMaximumRetailPrice() != null || request.getCostPrice() != null
        || request.getPriceToRetail() != null || request.getSaleAdditionalDiscount() != null) {
      validatePriceValues(request.getMaximumRetailPrice(), request.getCostPrice(),
          request.getPriceToRetail(), request.getSaleAdditionalDiscount());
    }
  }

  public void validateShopId(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("Shop ID is required");
    }
  }

  public void validatePricingBelongsToShop(boolean belongs) {
    if (!belongs) {
      throw new ValidationException("Pricing does not belong to your shop");
    }
  }

  public void validatePricingId(String pricingId) {
    if (!StringUtils.hasText(pricingId)) {
      throw new ValidationException("Pricing ID is required");
    }
  }

  /** Validate API create command before mapping to internal request. */
  public void validateCreateCommand(PricingCreateCommand command) {
    if (command == null) {
      throw new ValidationException("Create pricing command cannot be null");
    }
    if (!StringUtils.hasText(command.getShopId())) {
      throw new ValidationException("Shop ID is required for pricing");
    }
  }

  /** Validate API update command before mapping to internal request. */
  public void validateUpdateCommand(PricingUpdateCommand command) {
    if (command == null) {
      throw new ValidationException("Update pricing command cannot be null");
    }
  }

  /**
   * Validate update default price request. At least one of rates or defaultRate must be provided.
   */
  public void validateUpdateDefaultPriceRequest(UpdateDefaultPriceRequest request, List<Rate> effectiveRates, String effectiveDefaultRate) {
    if (request == null) {
      throw new ValidationException("Update request cannot be null");
    }
    boolean hasRates = request.getRates() != null && !request.getRates().isEmpty();
    boolean hasDefaultRate = StringUtils.hasText(request.getDefaultRate());
    if (!hasRates && !hasDefaultRate) {
      throw new ValidationException("At least one of rates or defaultRate must be provided");
    }
    List<Rate> ratesToValidate = hasRates ? request.getRates() : effectiveRates;
    String defaultRateToValidate = hasDefaultRate ? request.getDefaultRate() : effectiveDefaultRate;
    if (ratesToValidate != null) {
      validateRates(ratesToValidate, defaultRateToValidate);
    }
  }

  public void validateBulkUpdateRequest(List<UpdateDefaultPriceItem> updates) {
    if (updates == null || updates.isEmpty()) {
      throw new ValidationException("Updates list cannot be null or empty");
    }
  }

  public void validateUpdateDefaultPriceItem(UpdateDefaultPriceItem item, List<Rate> effectiveRates, String effectiveDefaultRate) {
    if (item == null) {
      throw new ValidationException("Update item cannot be null");
    }
    if (!StringUtils.hasText(item.getPricingId())) {
      throw new ValidationException("Pricing ID is required for each update item");
    }
    boolean hasRates = item.getRates() != null && !item.getRates().isEmpty();
    boolean hasDefaultRate = StringUtils.hasText(item.getDefaultRate());
    if (!hasRates && !hasDefaultRate) {
      throw new ValidationException("At least one of rates or defaultRate must be provided");
    }
    List<Rate> ratesToValidate = hasRates ? item.getRates() : effectiveRates;
    String defaultRateToValidate = hasDefaultRate ? item.getDefaultRate() : effectiveDefaultRate;
    if (ratesToValidate != null) {
      validateRates(ratesToValidate, defaultRateToValidate);
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
    if (StringUtils.hasText(defaultRate)
        && !"priceToRetail".equalsIgnoreCase(defaultRate)
        && !"maximumRetailPrice".equalsIgnoreCase(defaultRate)
        && !"costPrice".equalsIgnoreCase(defaultRate)) {
      if (rates == null || rates.isEmpty()) {
        throw new ValidationException("defaultRate '" + defaultRate + "' requires rates, or use 'priceToRetail', 'maximumRetailPrice', or 'costPrice'");
      }
      boolean found = rates.stream().anyMatch(r -> defaultRate.equals(r.getName()));
      if (!found) {
        throw new ValidationException("defaultRate '" + defaultRate + "' must match a rate name in rates, or use 'priceToRetail', 'maximumRetailPrice', or 'costPrice'");
      }
    }
  }

  private static BigDecimal resolveEffectivePrice(BigDecimal mrp, BigDecimal costPrice, BigDecimal priceToRetail, List<Rate> rates, String defaultRate) {
    if (!StringUtils.hasText(defaultRate)) {
      return priceToRetail;
    }
    String dr = defaultRate.trim();
    if ("maximumRetailPrice".equalsIgnoreCase(dr)) {
      return mrp != null ? mrp : priceToRetail;
    }
    if ("costPrice".equalsIgnoreCase(dr)) {
      return costPrice != null ? costPrice : priceToRetail;
    }
    if ("priceToRetail".equalsIgnoreCase(dr)) {
      return priceToRetail;
    }
    if (rates != null && !rates.isEmpty()) {
      return rates.stream()
          .filter(r -> dr.equals(r.getName()))
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
