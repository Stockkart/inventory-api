package com.inventory.user.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.LedgerPartyType;
import com.inventory.user.rest.dto.request.CreateLedgerEntryRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

@Component
public class CreditLedgerValidator {

  public void validateCreateLedgerEntryRequest(CreateLedgerEntryRequest request) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    if (request.getPartyType() == null) {
      throw new ValidationException("partyType is required");
    }
    if (!StringUtils.hasText(request.getPartyId())) {
      throw new ValidationException("partyId is required");
    }
    if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
      throw new ValidationException("amount must be positive");
    }
    if (request.getType() == null) {
      throw new ValidationException("type is required");
    }
    if (request.getSource() == null) {
      throw new ValidationException("source is required");
    }
  }

  public void validateGetBalanceParams(String shopId, LedgerPartyType partyType, String partyId) {
    if (!StringUtils.hasText(shopId) || partyType == null || !StringUtils.hasText(partyId)) {
      throw new ValidationException("shopId, partyType, and partyId are required");
    }
  }
}
