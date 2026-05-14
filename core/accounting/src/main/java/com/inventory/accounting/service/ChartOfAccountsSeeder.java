package com.inventory.accounting.service;

import static com.inventory.accounting.domain.model.SystemAccountCode.*;

import com.inventory.accounting.domain.model.Account;
import com.inventory.accounting.domain.model.AccountType;
import com.inventory.accounting.domain.model.NormalBalance;
import com.inventory.accounting.domain.repository.AccountRepository;
import com.inventory.accounting.domain.repository.LedgerEntryRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Seeds the canonical default Chart of Accounts for a given shop. Idempotent: only inserts entries
 * whose {@code code} is not yet present, so repeated invocations and partial historical seeds are
 * safely converged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChartOfAccountsSeeder {

  private final AccountRepository accountRepository;
  private final LedgerEntryRepository ledgerEntryRepository;

  /**
   * Codes that used to be seeded but have been retired (no longer part of the canonical CoA).
   * On {@link #seedShop(String)} we drop these for any shop that has no ledger activity against
   * them so legacy shops stop seeing dangling rows in the trial balance.
   */
  private static final Set<String> RETIRED_CODES = Set.of(INPUT_IGST, OUTPUT_IGST);

  /** Templates for the canonical system accounts seeded on first call per shop. */
  private static final List<Template> TEMPLATES =
      List.of(
          // Assets
          t(CASH, "Cash in Hand", AccountType.ASSET, NormalBalance.DEBIT, null),
          t(BANK, "Bank", AccountType.ASSET, NormalBalance.DEBIT, null),
          t(CARD_CLEARING, "Card / Gateway Clearing", AccountType.ASSET, NormalBalance.DEBIT, null),
          t(UPI_CLEARING, "UPI Clearing", AccountType.ASSET, NormalBalance.DEBIT, null),
          t(SUNDRY_DEBTORS, "Sundry Debtors", AccountType.ASSET, NormalBalance.DEBIT, null),
          t(INVENTORY, "Inventory (Stock-in-Trade)", AccountType.ASSET, NormalBalance.DEBIT, null),
          t(INPUT_CGST, "Input CGST", AccountType.ASSET, NormalBalance.DEBIT, null),
          t(INPUT_SGST, "Input SGST", AccountType.ASSET, NormalBalance.DEBIT, null),
          // IGST (interstate) accounts are intentionally not seeded — interstate posting will be
          // wired in once invoices capture a place-of-supply marker. The constants live in
          // SystemAccountCode so we can re-introduce these rows without a migration.
          // Liabilities
          t(SUNDRY_CREDITORS, "Sundry Creditors", AccountType.LIABILITY, NormalBalance.CREDIT, null),
          t(OUTPUT_CGST, "Output CGST", AccountType.LIABILITY, NormalBalance.CREDIT, null),
          t(OUTPUT_SGST, "Output SGST", AccountType.LIABILITY, NormalBalance.CREDIT, null),
          t(ROUND_OFF_PAYABLE, "Round-off Payable", AccountType.LIABILITY, NormalBalance.CREDIT, null),
          // Equity
          t(OWNERS_CAPITAL, "Owner's Capital", AccountType.EQUITY, NormalBalance.CREDIT, null),
          t(RETAINED_EARNINGS, "Retained Earnings", AccountType.EQUITY, NormalBalance.CREDIT, null),
          // Revenue
          t(SALES, "Sales", AccountType.REVENUE, NormalBalance.CREDIT, null),
          t(SALES_RETURNS, "Sales Returns", AccountType.REVENUE, NormalBalance.DEBIT, null),
          t(DISCOUNT_ALLOWED, "Discount Allowed", AccountType.REVENUE, NormalBalance.DEBIT, null),
          // Expenses
          t(PURCHASES, "Purchases", AccountType.EXPENSE, NormalBalance.DEBIT, null),
          t(PURCHASE_RETURNS, "Purchase Returns", AccountType.EXPENSE, NormalBalance.CREDIT, null),
          t(COGS, "Cost of Goods Sold", AccountType.EXPENSE, NormalBalance.DEBIT, null),
          t(SHIPPING_FREIGHT, "Shipping & Freight", AccountType.EXPENSE, NormalBalance.DEBIT, null),
          t(ROUND_OFF_EXPENSE, "Round-off Expense", AccountType.EXPENSE, NormalBalance.DEBIT, null),
          t(OTHER_OPERATING_EXPENSES,
              "Other Operating Expenses",
              AccountType.EXPENSE,
              NormalBalance.DEBIT,
              null));

  /** Insert any missing system accounts for {@code shopId}. Returns the count actually inserted. */
  public int seedShop(String shopId) {
    if (shopId == null || shopId.isBlank()) {
      throw new IllegalArgumentException("shopId is required");
    }
    List<Account> currentAccounts = accountRepository.findByShopIdOrderByCodeAsc(shopId);
    pruneRetiredAccounts(shopId, currentAccounts);
    Set<String> existing = new HashSet<>();
    for (Account a : currentAccounts) {
      if (a.getCode() != null && !RETIRED_CODES.contains(a.getCode())) {
        existing.add(a.getCode());
      }
    }
    List<Account> toInsert = new ArrayList<>();
    Instant now = Instant.now();
    for (Template t : TEMPLATES) {
      if (!existing.contains(t.code)) {
        Account a = new Account();
        a.setShopId(shopId);
        a.setCode(t.code);
        a.setName(t.name);
        a.setType(t.type);
        a.setNormalBalance(t.normal);
        a.setParentCode(t.parent);
        a.setSystem(true);
        a.setActive(true);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        toInsert.add(a);
      }
    }
    if (toInsert.isEmpty()) {
      return 0;
    }
    accountRepository.saveAll(toInsert);
    log.info("Seeded {} chart-of-accounts entries for shop {}", toInsert.size(), shopId);
    return toInsert.size();
  }

  /** Read-only view of which {@code code} values are part of the seeded canonical CoA. */
  public Set<String> canonicalCodes() {
    Set<String> codes = new HashSet<>();
    TEMPLATES.forEach(t -> codes.add(t.code));
    return codes;
  }

  /** Returns the seeded ordering for downstream display helpers. */
  public List<String> canonicalCodesOrdered() {
    return Arrays.stream(TEMPLATES.stream().map(t -> t.code).toArray(String[]::new)).toList();
  }

  /**
   * Deletes any retired-code account that has no ledger activity. Retains the account when there
   * are still ledger rows pointing at it so we never break referential integrity — the caller
   * (typically the admin "rebuild books" flow) should re-post entries to drop those rows first.
   */
  private void pruneRetiredAccounts(String shopId, List<Account> currentAccounts) {
    for (Account a : currentAccounts) {
      if (a.getCode() == null || !RETIRED_CODES.contains(a.getCode())) continue;
      boolean hasLedger =
          !ledgerEntryRepository
              .findByShopIdAndAccountIdOrderByPostedAtAsc(shopId, a.getId(), PageRequest.of(0, 1))
              .isEmpty();
      if (hasLedger) {
        log.debug(
            "Skipping prune of retired account {} for shop {}: still has ledger rows",
            a.getCode(),
            shopId);
        continue;
      }
      accountRepository.delete(a);
      log.info("Pruned retired account {} ({}) for shop {}", a.getCode(), a.getName(), shopId);
    }
  }

  private static Template t(
      String code, String name, AccountType type, NormalBalance normal, String parent) {
    return new Template(code, name, type, normal, parent);
  }

  private record Template(
      String code, String name, AccountType type, NormalBalance normal, String parent) {}
}
