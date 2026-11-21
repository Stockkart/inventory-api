package com.inventory.credit.service;

import com.inventory.credit.domain.model.CreditEntry;
import com.inventory.credit.rest.dto.request.CreateCreditSettlementRequest;

/**
 * Settlement entry point used by {@link com.inventory.credit.rest.controller.CreditController}.
 * Implemented by {@link AccountingCreditSettlementFacade}, which posts the journal entry in the
 * same transaction.
 */
public interface CreditSettlementFacade {

  CreditEntry createSettlement(String shopId, String userId, CreateCreditSettlementRequest body);
}
