package com.inventory.accounting.rest.dto.response;

import com.inventory.accounting.domain.model.JournalPostingSource;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@AllArgsConstructor
public class JournalEntryResponse {

  private String id;
  private String shopId;
  private Instant journalDate;
  private Instant postedAt;
  private String description;
  private JournalPostingSource source;
  private String sourceKey;
  private BigDecimal totalDebitSum;
  private BigDecimal totalCreditSum;
  private String postedByUserId;
  private List<JournalLineResponse> lines;
}
