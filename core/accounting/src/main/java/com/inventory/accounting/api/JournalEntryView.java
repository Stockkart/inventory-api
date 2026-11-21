package com.inventory.accounting.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * A posted journal entry as other modules see it: what {@link AccountingFacade} returns instead
 * of the stored document. Carries what callers check after posting (the id, whether it has been
 * reversed, and the totals and lines a caller compares with what it meant to post).
 *
 * @param id journal entry id
 * @param sourceType business event the entry reflects
 * @param sourceId id of that event in its own module
 * @param status {@link JournalStatus#REVERSED} once a reversal has been posted for it
 * @param reversedByEntryId id of the reversing entry; null unless reversed
 * @param totalDebit sum of line debits
 * @param totalCredit sum of line credits
 * @param lines entry lines in posting order; never null
 */
public record JournalEntryView(
    String id,
    JournalSource sourceType,
    String sourceId,
    JournalStatus status,
    String reversedByEntryId,
    BigDecimal totalDebit,
    BigDecimal totalCredit,
    List<JournalLineView> lines) {

  public JournalEntryView {
    lines = lines == null ? List.of() : List.copyOf(lines);
  }
}
