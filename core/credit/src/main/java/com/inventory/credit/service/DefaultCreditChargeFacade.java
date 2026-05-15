package com.inventory.credit.service;

import com.inventory.credit.domain.model.CreditEntry;
import com.inventory.credit.rest.dto.request.CreateCreditChargeRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Credit-only charge; overridden by {@code @Primary} orchestrator in the product module. */
@Service
@RequiredArgsConstructor
public class DefaultCreditChargeFacade implements CreditChargeFacade {

  private final CreditService creditService;

  @Override
  public CreditEntry createCharge(String shopId, String userId, CreateCreditChargeRequest body) {
    return creditService.createCharge(shopId, userId, body);
  }
}
