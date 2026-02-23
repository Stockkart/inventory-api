package com.inventory.pricing.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.pricing.rest.dto.CreatePricingRequest;
import com.inventory.pricing.rest.dto.UpdatePricingRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

@Component
public class PricingValidator {

  public void validateCreateRequest(CreatePricingRequest request) {
    if (request == null) {
      throw new ValidationException("Create pricing request cannot be null");
    }
    if (!StringUtils.hasText(request.getShopId())) {
      throw new ValidationException("Shop ID is required for pricing");
    }
    validatePriceValues(request.getMaximumRetailPrice(), request.getCostPrice(),
        request.getSellingPrice(), request.getAdditionalDiscount());
    validateGstRates(request.getSgst(), request.getCgst());
  }

  public void validateUpdateRequest(UpdatePricingRequest request) {
    if (request == null) {
      return;
    }
    validatePriceValues(request.getMaximumRetailPrice(), request.getCostPrice(),
        request.getSellingPrice(), request.getAdditionalDiscount());
    validateGstRates(request.getSgst(), request.getCgst());
  }

  public void validatePricingId(String pricingId) {
    if (!StringUtils.hasText(pricingId)) {
      throw new ValidationException("Pricing ID is required");
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
