package com.inventory.accounting.api;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;

/**
 * Caller-built journal entry passed to {@link AccountingFacade#post(String, String,
 * PostJournalRequest)}. Sum of debits must equal sum of credits; {@code sourceType} + {@code
 * sourceId} provides idempotency.
 */
@Builder
public record PostJournalRequest(
    JournalSource sourceType,
    String sourceId,
    String sourceKey,
    LocalDate txnDate,
    String narration,
    List<PostJournalLine> lines) {

  /** No lines given means an empty, modifiable list, as when this was a mutable class. */
  public PostJournalRequest {
    lines = lines == null ? new ArrayList<>() : lines;
  }
}
