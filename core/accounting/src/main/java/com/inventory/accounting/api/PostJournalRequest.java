package com.inventory.accounting.api;

import com.inventory.accounting.domain.model.JournalSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Caller-built journal entry passed to {@link AccountingFacade#post(String, String,
 * PostJournalRequest)}. Sum of debits must equal sum of credits; {@code sourceType} + {@code
 * sourceId} provides idempotency.
 */
@Data
public class PostJournalRequest {

  private JournalSource sourceType;
  private String sourceId;
  private String sourceKey;

  private LocalDate txnDate;
  private String narration;

  private List<PostJournalLine> lines = new ArrayList<>();
}
