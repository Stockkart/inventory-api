package com.inventory.accounting.service;

import com.inventory.accounting.domain.model.GlAccount;
import com.inventory.accounting.domain.model.JournalEntry;
import com.inventory.accounting.domain.model.JournalLine;
import com.inventory.accounting.domain.repository.GlAccountRepository;
import com.inventory.accounting.domain.repository.JournalEntryRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Consolidates accidental duplicate chart rows per (shopId, code). Legacy duplicates happen when runs
 * race before MongoDB's unique compound index existed, so both inserts bypassed duplicate checks.
 * Journal legs store {@link JournalLine#getAccountId()} — those must point at the survivor before
 * extra {@link GlAccount} documents are deleted.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GlChartDedupeService {

  private final GlAccountRepository glAccountRepository;
  private final JournalEntryRepository journalEntryRepository;

  @Transactional
  public void dedupeChartAccountsForShop(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      return;
    }
    String sid = shopId.trim();
    List<GlAccount> rows = glAccountRepository.findByShopIdOrderByCodeAsc(sid);
    Map<String, List<GlAccount>> byCode =
        new LinkedHashMap<>();
    for (GlAccount row : rows) {
      String key = normalizeCode(row.getCode());
      if (!StringUtils.hasText(key)) {
        continue;
      }
      byCode.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
    }

    int removedAccounts = 0;
    int updatedJournals = 0;

    for (Map.Entry<String, List<GlAccount>> e : byCode.entrySet()) {
      List<GlAccount> group = e.getValue();
      if (group.size() <= 1) {
        continue;
      }
      group.sort(Comparator.comparing(GlAccount::getId, Comparator.nullsLast(String::compareTo)));
      GlAccount keeper = group.get(0);
      for (int i = 1; i < group.size(); i++) {
        GlAccount dup = group.get(i);
        if (dup.getId() == null || dup.getId().equals(keeper.getId())) {
          continue;
        }
        updatedJournals +=
            rewriteJournalAccountIdsInternal(sid, dup.getId(), keeper.getId(), keeper.getCode());
        glAccountRepository.deleteById(dup.getId());
        removedAccounts++;
        log.info(
            "Removed duplicate GlAccount id={} code={} shopId={}, merged into canonical id={}",
            dup.getId(),
            keeper.getCode(),
            sid,
            keeper.getId());
      }
    }

    if (removedAccounts > 0) {
      log.warn(
          "Deduped GL chart for shopId={}: removed {} duplicate account rows, touched {} journals",
          sid,
          removedAccounts,
          updatedJournals);
    }
  }

  private int rewriteJournalAccountIdsInternal(
      String shopId, String fromAccountId, String toAccountId, String snapshotAccountCode) {
    List<JournalEntry> hits =
        journalEntryRepository.findByShopIdAndLineAccountId(shopId, fromAccountId);
    int journalsChanged = 0;
    for (JournalEntry je : hits) {
      if (je.getLines() == null || je.getLines().isEmpty()) {
        continue;
      }
      boolean changed = false;
      for (JournalLine line : je.getLines()) {
        if (Objects.equals(fromAccountId, line.getAccountId())) {
          line.setAccountId(toAccountId);
          if (snapshotAccountCode != null) {
            line.setAccountCode(snapshotAccountCode);
          }
          changed = true;
        }
      }
      if (changed) {
        journalEntryRepository.save(je);
        journalsChanged++;
      }
    }
    return journalsChanged;
  }

  private static String normalizeCode(String code) {
    return code == null ? "" : code.trim();
  }
}
