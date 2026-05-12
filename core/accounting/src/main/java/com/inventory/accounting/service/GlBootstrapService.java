package com.inventory.accounting.service;

import com.inventory.accounting.domain.model.AccountType;
import com.inventory.accounting.domain.model.DefaultAccountCodes;
import com.inventory.accounting.domain.model.GlAccount;
import com.inventory.accounting.domain.repository.GlAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class GlBootstrapService {

  private final GlAccountRepository glAccountRepository;
  private final GlChartDedupeService glChartDedupeService;
  private final LegacyGlChartCodeMigrator legacyGlChartCodeMigrator;

  private record Seed(String code, String name, AccountType type) {}

  private static final List<Seed> SEEDS =
      List.of(
          new Seed(
              DefaultAccountCodes.CASH,
              "Cash — receipts when customers pay you (checkout, cash-in)",
              AccountType.ASSET),
          new Seed(
              DefaultAccountCodes.BANK,
              "Bank — online/UPI/card payments (for bank reconciliation)",
              AccountType.ASSET),
          new Seed(
              DefaultAccountCodes.ACCOUNTS_RECEIVABLE,
              "Accounts receivable (customers)",
              AccountType.ASSET),
          new Seed(DefaultAccountCodes.OWNER_EQUITY, "Owner's equity / capital", AccountType.EQUITY),
          new Seed(DefaultAccountCodes.SALES_REVENUE, "Sales revenue", AccountType.REVENUE),
          new Seed(
              DefaultAccountCodes.PURCHASES_EXPENSE,
              "Purchases — stock cost (ex-GST); vendor buys accrue payable, not cash",
              AccountType.EXPENSE),
          new Seed(
              DefaultAccountCodes.GST_OUTPUT_COMBINED,
              "GST output (collected)",
              AccountType.LIABILITY),
          new Seed(
              DefaultAccountCodes.GST_INPUT_COMBINED,
              "GST input credit (ITC receivable)",
              AccountType.ASSET));

  /**
   * Full bootstrap (dedupe, legacy code migration, missing seeds, system label refresh) — costly;
   * run before postings, manual accounts, POST /chart/bootstrap, and vendor-payable provisioning.
   */
  @Transactional
  public void ensureDefaultsForShop(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      return;
    }
    glChartDedupeService.dedupeChartAccountsForShop(shopId);
    legacyGlChartCodeMigrator.migrateIfNeeded(shopId);
    seedMissingNominals(shopId);
    refreshSystemAccountLabels(shopId);
  }

  /**
   * Inserts missing built-in nominal rows only — no chart dedupe/migration churn. Prefer on hot read
   * paths (trial balance list, GET gl-accounts) so dashboards stay responsive.
   */
  @Transactional
  public void ensureSeedAccountsOnly(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      return;
    }
    seedMissingNominals(shopId);
  }

  private void seedMissingNominals(String shopId) {
    String sid = shopId.trim();
    for (Seed s : SEEDS) {
      if (glAccountRepository.existsByShopIdAndCode(sid, s.code)) {
        continue;
      }
      GlAccount row = new GlAccount();
      row.setShopId(sid);
      row.setCode(s.code);
      row.setName(s.name);
      row.setAccountType(s.type);
      row.setSystemAccount(true);
      row.setActive(true);
      row.setCreatedAt(Instant.now());
      try {
        glAccountRepository.save(row);
      } catch (DuplicateKeyException ignored) {
        // Concurrent bootstrap / race before unique index — row already exists for (shopId, code).
      }
    }
  }

  /** Align display names when seed wording changes (system accounts only). */
  private void refreshSystemAccountLabels(String shopId) {
    Map<String, String> nameByCode = new HashMap<>();
    for (Seed s : SEEDS) {
      nameByCode.put(s.code, s.name);
    }
    for (GlAccount a : glAccountRepository.findByShopIdOrderByCodeAsc(shopId)) {
      if (!a.isSystemAccount()) {
        continue;
      }
      String canonical = nameByCode.get(a.getCode());
      if (canonical != null && !canonical.equals(a.getName())) {
        a.setName(canonical);
        glAccountRepository.save(a);
      }
    }
  }
}
