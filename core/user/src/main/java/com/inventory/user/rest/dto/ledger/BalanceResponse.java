package com.inventory.user.rest.dto.ledger;

import com.inventory.user.domain.model.LedgerPartyType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BalanceResponse {

  private String shopId;
  private LedgerPartyType partyType;
  private String partyId;
  private BigDecimal balance;
}
