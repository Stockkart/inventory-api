package com.inventory.accounting.rest.dto.response;

import com.inventory.accounting.domain.model.JournalSource;
import com.inventory.accounting.domain.model.PartyType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LedgerEntryResponse {
  private String id;
  private String journalEntryId;
  private String journalEntryNo;
  private JournalSource sourceType;
  private String sourceId;
  private LocalDate txnDate;
  private Instant postedAt;
  private BigDecimal debit;
  private BigDecimal credit;
  private BigDecimal balanceAfter;
  private PartyType partyType;
  private String partyRefId;
  private String partyDisplayName;
  private String narration;
}
