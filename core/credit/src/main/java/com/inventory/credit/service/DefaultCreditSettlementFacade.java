package com.inventory.credit.service;

import com.inventory.credit.domain.model.CreditEntry;
import com.inventory.credit.rest.dto.request.CreateCreditSettlementRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Credit-only settlement; overridden by {@code @Primary} orchestrator in the product module. */
@Service
@RequiredArgsConstructor
public class DefaultCreditSettlementFacade implements CreditSettlementFacade {

  private final CreditService creditService;

  @Override
  public CreditEntry createSettlement(
      String shopId, String userId, CreateCreditSettlementRequest body) {
    return creditService.createSettlement(shopId, userId, body);
  }
}
