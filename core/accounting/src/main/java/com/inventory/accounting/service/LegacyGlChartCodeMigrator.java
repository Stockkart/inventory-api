package com.inventory.accounting.service;

import com.inventory.accounting.domain.model.DefaultAccountCodes;
import com.inventory.accounting.domain.model.GlAccount;
import com.inventory.accounting.domain.model.JournalEntry;
import com.inventory.accounting.domain.model.JournalLine;
import com.inventory.accounting.domain.repository.GlAccountRepository;
import com.inventory.accounting.domain.repository.JournalEntryRepository;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Renames historical chart aliases to {@link DefaultAccountCodes} (order matters: AST/LIA/… first,
 * then older {@code SK-*} forms, ending at prefix-free canonical codes).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LegacyGlChartCodeMigrator {

  private final GlAccountRepository glAccountRepository;
  private final JournalEntryRepository journalEntryRepository;

  /** Oldest abbreviated forms → then {@code SK-*} → final codes (targets match seeded constants). */
  private static final List<Map.Entry<String, String>> STEPS =
      List.of(
          new SimpleImmutableEntry<>("SK-AST-CASH", DefaultAccountCodes.CASH),
          new SimpleImmutableEntry<>("SK-AST-AR", DefaultAccountCodes.ACCOUNTS_RECEIVABLE),
          new SimpleImmutableEntry<>("SK-LIA-AP", DefaultAccountCodes.ACCOUNTS_PAYABLE),
          new SimpleImmutableEntry<>("SK-EQY-CAPITAL", DefaultAccountCodes.OWNER_EQUITY),
          new SimpleImmutableEntry<>("SK-REV-SALES", DefaultAccountCodes.SALES_REVENUE),
          new SimpleImmutableEntry<>("SK-EXP-PURCHASE", DefaultAccountCodes.PURCHASES_EXPENSE),
          new SimpleImmutableEntry<>("SK-LIA-GST-SGST", DefaultAccountCodes.GST_OUTPUT_COMBINED),
          new SimpleImmutableEntry<>("SK-LIA-GST-CGST", DefaultAccountCodes.GST_OUTPUT_COMBINED),
          new SimpleImmutableEntry<>("SK-LIA-GST-OUT", DefaultAccountCodes.GST_OUTPUT_COMBINED),
          new SimpleImmutableEntry<>("SK-AST-GST-IN", DefaultAccountCodes.GST_INPUT_COMBINED),
          new SimpleImmutableEntry<>("SK-CASH", DefaultAccountCodes.CASH),
          new SimpleImmutableEntry<>("SK-RECEIVABLES", DefaultAccountCodes.ACCOUNTS_RECEIVABLE),
          new SimpleImmutableEntry<>("SK-PAYABLES", DefaultAccountCodes.ACCOUNTS_PAYABLE),
          new SimpleImmutableEntry<>("SK-EQUITY", DefaultAccountCodes.OWNER_EQUITY),
          new SimpleImmutableEntry<>("SK-SALES", DefaultAccountCodes.SALES_REVENUE),
          new SimpleImmutableEntry<>("SK-PURCHASES", DefaultAccountCodes.PURCHASES_EXPENSE),
          new SimpleImmutableEntry<>("SK-GST-SGST", DefaultAccountCodes.GST_OUTPUT_COMBINED),
          new SimpleImmutableEntry<>("SK-GST-CGST", DefaultAccountCodes.GST_OUTPUT_COMBINED),
          new SimpleImmutableEntry<>("SK-GST-OUTPUT", DefaultAccountCodes.GST_OUTPUT_COMBINED),
          new SimpleImmutableEntry<>("SK-GST-INPUT", DefaultAccountCodes.GST_INPUT_COMBINED),
          new SimpleImmutableEntry<>("GST-SGST", DefaultAccountCodes.GST_OUTPUT_COMBINED),
          new SimpleImmutableEntry<>("GST-CGST", DefaultAccountCodes.GST_OUTPUT_COMBINED));

  @Transactional
  public void migrateIfNeeded(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      return;
    }
    String sid = shopId.trim();
    for (Map.Entry<String, String> step : STEPS) {
      migratePair(sid, step.getKey(), step.getValue());
    }
  }

  private void migratePair(String shopId, String legacyCode, String newCode) {
    Optional<GlAccount> legacyOpt =
        glAccountRepository.findFirstByShopIdAndCodeOrderByIdAsc(shopId, legacyCode);
    if (legacyOpt.isEmpty()) {
      return;
    }
    GlAccount legacy = legacyOpt.get();
    Optional<GlAccount> modernOpt =
        glAccountRepository.findFirstByShopIdAndCodeOrderByIdAsc(shopId, newCode);

    if (modernOpt.isEmpty()) {
      legacy.setCode(newCode);
      glAccountRepository.save(legacy);
      touchSnapshotsCode(shopId, legacy.getId(), newCode);
      log.info(
          "Renamed GL account code {} → {} shopId={}, id={}",
          legacyCode,
          newCode,
          shopId,
          legacy.getId());
      return;
    }

    GlAccount survivor = modernOpt.get();
    if (legacy.getId().equals(survivor.getId())) {
      return;
    }
    rewriteJournalRefs(shopId, legacy.getId(), survivor.getId(), survivor.getCode());
    glAccountRepository.deleteById(legacy.getId());
    log.warn(
        "Merged duplicate chart {} ({}) → {} ({}) shopId={}",
        legacyCode,
        legacy.getId(),
        survivor.getCode(),
        survivor.getId(),
        shopId);
  }

  private void rewriteJournalRefs(
      String shopId, String fromAccountId, String toAccountId, String snapshotCode) {
    List<JournalEntry> hits =
        journalEntryRepository.findByShopIdAndLineAccountId(shopId, fromAccountId);
    for (JournalEntry je : hits) {
      if (je.getLines() == null || je.getLines().isEmpty()) {
        continue;
      }
      boolean changed = false;
      for (JournalLine line : je.getLines()) {
        if (Objects.equals(fromAccountId, line.getAccountId())) {
          line.setAccountId(toAccountId);
          line.setAccountCode(snapshotCode);
          changed = true;
        }
      }
      if (changed) {
        journalEntryRepository.save(je);
      }
    }
  }

  private void touchSnapshotsCode(String shopId, String accountId, String newCode) {
    List<JournalEntry> hits =
        journalEntryRepository.findByShopIdAndLineAccountId(shopId, accountId);
    for (JournalEntry je : hits) {
      if (je.getLines() == null || je.getLines().isEmpty()) {
        continue;
      }
      boolean changed = false;
      for (JournalLine line : je.getLines()) {
        if (Objects.equals(accountId, line.getAccountId())
            && !Objects.equals(newCode, line.getAccountCode())) {
          line.setAccountCode(newCode);
          changed = true;
        }
      }
      if (changed) {
        journalEntryRepository.save(je);
      }
    }
  }
}
