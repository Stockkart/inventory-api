package com.inventory.credit.service;

import com.inventory.credit.domain.model.CreditEntry;
import com.inventory.credit.rest.dto.request.CreateCreditChargeRequest;

/**
 * Charge entry point used by {@link com.inventory.credit.rest.controller.CreditController} and,
 * through {@link com.inventory.credit.api.CreditLedger#charge}, by other modules. Implemented by
 * {@link AccountingCreditChargeFacade}, which also posts the journal entry when one is needed.
 */
public interface CreditChargeFacade {

  CreditEntry createCharge(String shopId, String userId, CreateCreditChargeRequest body);
}
