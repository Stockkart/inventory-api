package com.inventory.accounting.service;

import com.inventory.accounting.api.JournalEntryView;
import com.inventory.accounting.api.JournalLineView;
import com.inventory.accounting.domain.model.JournalEntry;
import java.util.List;
import java.util.Objects;

/** Maps a stored {@link JournalEntry} to the {@link JournalEntryView} other modules receive. */
final class JournalEntryViews {

  private JournalEntryViews() {}

  /** Null in, null out, matching what the facade returned before it returned views. */
  static JournalEntryView of(JournalEntry e) {
    if (e == null) {
      return null;
    }
    List<JournalLineView> lines = e.getLines() == null
        ? List.of()
        : e.getLines().stream()
            .filter(Objects::nonNull)
            .map(l -> new JournalLineView(l.getAccountCode(), l.getDebit(), l.getCredit()))
            .toList();
    return new JournalEntryView(e.getId(), e.getSourceType(), e.getSourceId(), e.getStatus(),
        e.getReversedByEntryId(), e.getTotalDebit(), e.getTotalCredit(), lines);
  }
}
