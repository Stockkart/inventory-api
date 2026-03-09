package com.inventory.user.rest.dto.request;

import com.inventory.user.domain.model.LedgerEntryType;
import com.inventory.user.domain.model.LedgerPartyType;
import com.inventory.user.domain.model.LedgerReferenceType;
import com.inventory.user.domain.model.LedgerSource;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateLedgerEntryRequest {

  private LedgerPartyType partyType;
  private String partyId;
  private BigDecimal amount;
  private LedgerEntryType type;
  private LedgerSource source;
  private String referenceId;
  private LedgerReferenceType referenceType;
  private String description;
}
