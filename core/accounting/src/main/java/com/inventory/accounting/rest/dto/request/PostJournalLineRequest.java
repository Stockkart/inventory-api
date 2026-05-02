package com.inventory.accounting.rest.dto.request;

import com.inventory.accounting.domain.model.PartyType;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PostJournalLineRequest {

  /** Must match GlAccount#getCode() for the authenticated shop */
  @NotBlank private String accountCode;

  private BigDecimal debit;
  private BigDecimal credit;
  private String memo;
  /** Together with {@link #partyId} creates a subsidiary entry for AR/AP balances. */
  private PartyType partyType;
  private String partyId;
}
