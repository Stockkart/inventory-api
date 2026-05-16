package com.inventory.credit.service;

import com.inventory.credit.domain.model.CreditEntry;
import com.inventory.credit.rest.dto.request.CreateCreditSettlementRequest;

/**
 * Settlement entry point used by {@link com.inventory.credit.rest.controller.CreditController}.
 * The product module supplies an implementation that posts accounting journal entries in the
 * same transaction; a credit-only fallback is registered when accounting is not wired.
 */
public interface CreditSettlementFacade {

  CreditEntry createSettlement(String shopId, String userId, CreateCreditSettlementRequest body);
}
