package com.inventory.credit.rest.dto.response;

import com.inventory.credit.domain.model.CreditBalanceStatus;
import com.inventory.credit.domain.model.CreditPartyType;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CreditAccountResponse {

  private String id;
  private CreditPartyType partyType;
  private String partyId;
  private String partyDisplayName;
  private String partyPhone;
  private BigDecimal currentBalance;
  private CreditBalanceStatus status;
  private Instant updatedAt;
  private Instant lastEntryAt;
}
