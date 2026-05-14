package com.inventory.accounting.rest.dto.response;

import com.inventory.accounting.domain.model.JournalSource;
import com.inventory.accounting.domain.model.JournalStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JournalEntryResponse {
  private String id;
  private String entryNo;
  private LocalDate txnDate;
  private Instant postedAt;
  private JournalSource sourceType;
  private String sourceId;
  private JournalStatus status;
  private String reversesEntryId;
  private String reversedByEntryId;
  private String narration;
  private List<JournalLineResponse> lines;
  private BigDecimal totalDebit;
  private BigDecimal totalCredit;
  private String createdByUserId;
}
