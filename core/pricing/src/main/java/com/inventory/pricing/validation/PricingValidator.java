package com.inventory.pricing.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.rest.dto.CreatePricingRequest;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

@Component
public class PricingValidator {

  public void validate(CreatePricingRequest request) {

    //  Selling vs Cost validation
    if (request.getSellingPrice() != null &&
      request.getCostPrice() != null &&
      request.getSellingPrice().compareTo(request.getCostPrice()) < 0) {

      throw new ValidationException("Selling price cannot be less than cost price");
    }

    //  Rates validation + duplicate protection
    if (request.getRates() != null && !request.getRates().isEmpty()) {

      Set<String> uniqueNames = new HashSet<>();

      for (Rate rate : request.getRates()) {

        if (rate == null) {
          throw new ValidationException("Rate entry cannot be null");
        }

        if (!StringUtils.hasText(rate.getName())) {
          throw new ValidationException("Rate name is required");
        }

        if (rate.getPrice() == null ||
          rate.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
          throw new ValidationException("Rate price must be positive");
        }

        //  Case-insensitive duplicate check
        String normalized = rate.getName().trim().toLowerCase();

        if (!uniqueNames.add(normalized)) {
          throw new ValidationException(
            "Duplicate rate name not allowed: " + rate.getName()
          );
        }
      }
    }

    //  setPrice validation (optional but recommended)
    if (StringUtils.hasText(request.getDefaultPrice())) {

      boolean valid = false;

      if ("SELLING_PRICE".equalsIgnoreCase(request.getDefaultPrice())) {
        valid = true;
      }

      if (!valid && request.getRates() != null) {
        valid = request.getRates().stream()
          .anyMatch(rate ->
            rate.getName().equalsIgnoreCase(request.getDefaultPrice()));
      }

      if (!valid) {
        throw new ValidationException(
          "setPrice must match SELLING_PRICE or an existing rate name"
        );
      }
    }
  }
}
