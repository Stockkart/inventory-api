package com.inventory.accounting.service;

import com.inventory.accounting.domain.model.JournalEntry;
import com.inventory.accounting.domain.model.JournalSource;
import com.inventory.accounting.domain.repository.JournalEntryRepository;
import com.inventory.common.exception.ResourceNotFoundException;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-side queries over {@code journal_entries}. */
@Service
@RequiredArgsConstructor
public class JournalQueryService {

  private final JournalEntryRepository journalEntryRepository;

  @Transactional(readOnly = true)
  public Page<JournalEntry> list(
      String shopId, JournalSource sourceType, LocalDate from, LocalDate to, int page, int size) {
    int safePage = Math.max(page, 0);
    int safeSize = Math.min(Math.max(size, 1), 100);
    PageRequest pr =
        PageRequest.of(
            safePage,
            safeSize,
            Sort.by(Sort.Order.desc("txnDate"), Sort.Order.desc("postedAt")));
    if (sourceType != null) {
      return journalEntryRepository.findByShopIdAndSourceTypeOrderByTxnDateDescPostedAtDesc(
          shopId, sourceType, pr);
    }
    if (from != null && to != null) {
      return journalEntryRepository
          .findByShopIdAndTxnDateBetweenOrderByTxnDateDescPostedAtDesc(shopId, from, to, pr);
    }
    return journalEntryRepository.findByShopIdOrderByTxnDateDescPostedAtDesc(shopId, pr);
  }

  @Transactional(readOnly = true)
  public JournalEntry getOrThrow(String shopId, String id) {
    return journalEntryRepository
        .findByShopIdAndId(shopId, id)
        .orElseThrow(() -> new ResourceNotFoundException("JournalEntry", "id", id));
  }
}
