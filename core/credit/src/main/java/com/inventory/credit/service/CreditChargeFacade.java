package com.inventory.credit.service;

import com.inventory.credit.domain.model.CreditEntry;
import com.inventory.credit.rest.dto.request.CreateCreditChargeRequest;

/**
 * Charge entry point used by {@link com.inventory.credit.rest.controller.CreditController}.
 * The product module supplies an implementation that posts accounting journal entries when
 * appropriate; a credit-only fallback is registered when accounting is not wired.
 */
public interface CreditChargeFacade {

  CreditEntry createCharge(String shopId, String userId, CreateCreditChargeRequest body);
}
