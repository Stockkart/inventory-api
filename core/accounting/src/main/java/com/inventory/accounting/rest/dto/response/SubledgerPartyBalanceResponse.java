package com.inventory.accounting.rest.dto.response;

import com.inventory.accounting.domain.model.PartyType;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class SubledgerPartyBalanceResponse {
  private String shopId;
  private PartyType partyType;
  private String partyId;
  /** Net balance as defined for that party flavour (positive = owe / owed). */
  private BigDecimal balance;
  /** Human hint: e.g. "Positive = amount you owe the vendor". */
  private String interpretationHint;
}
