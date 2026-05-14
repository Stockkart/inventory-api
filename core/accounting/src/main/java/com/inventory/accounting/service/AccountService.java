package com.inventory.accounting.service;

import com.inventory.accounting.domain.model.Account;
import com.inventory.accounting.domain.model.AccountType;
import com.inventory.accounting.domain.model.NormalBalance;
import com.inventory.accounting.domain.repository.AccountRepository;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Read access plus create/update for shop-defined accounts. System accounts seeded by {@link
 * ChartOfAccountsSeeder} are immutable except for the {@code active} flag.
 */
@Service
@RequiredArgsConstructor
public class AccountService {

  private final AccountRepository accountRepository;
  private final ChartOfAccountsSeeder seeder;

  @Transactional(readOnly = true)
  public List<Account> list(String shopId) {
    ensureSeeded(shopId);
    return accountRepository.findByShopIdOrderByCodeAsc(shopId);
  }

  @Transactional(readOnly = true)
  public Account findByCodeOrThrow(String shopId, String code) {
    return accountRepository
        .findByShopIdAndCode(shopId, code)
        .orElseThrow(() -> new ResourceNotFoundException("Account", "code", code));
  }

  @Transactional(readOnly = true)
  public Account findByIdOrThrow(String shopId, String id) {
    Account a =
        accountRepository
            .findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Account", "id", id));
    if (!shopId.equals(a.getShopId())) {
      throw new ValidationException("Account does not belong to authenticated shop");
    }
    return a;
  }

  /**
   * Idempotently makes sure the canonical CoA exists for {@code shopId}. Safe to call on every
   * accounting read.
   *
   * <p>We always delegate to {@link ChartOfAccountsSeeder#seedShop(String)} (rather than
   * short-circuiting when the list is non-empty) so that retired system accounts — e.g. legacy
   * IGST rows from before the CoA was trimmed — are pruned on the next read, provided they have
   * no ledger activity. The seeder's insert path remains idempotent.
   */
  public void ensureSeeded(String shopId) {
    seeder.seedShop(shopId);
  }

  @Transactional
  public Account create(
      String shopId, String code, String name, AccountType type, NormalBalance normalBalance) {
    if (!StringUtils.hasText(code)) {
      throw new ValidationException("Account code is required");
    }
    if (!StringUtils.hasText(name)) {
      throw new ValidationException("Account name is required");
    }
    if (type == null) {
      throw new ValidationException("Account type is required");
    }
    if (normalBalance == null) {
      normalBalance = defaultNormalBalance(type);
    }
    String normalizedCode = code.trim();
    if (accountRepository.existsByShopIdAndCode(shopId, normalizedCode)) {
      throw new ValidationException("Account code already exists: " + normalizedCode);
    }
    if (seeder.canonicalCodes().contains(normalizedCode)) {
      throw new ValidationException(
          "Code " + normalizedCode + " is reserved for a system account");
    }
    Instant now = Instant.now();
    Account a = new Account();
    a.setShopId(shopId);
    a.setCode(normalizedCode);
    a.setName(name.trim());
    a.setType(type);
    a.setNormalBalance(normalBalance);
    a.setSystem(false);
    a.setActive(true);
    a.setCreatedAt(now);
    a.setUpdatedAt(now);
    return accountRepository.save(a);
  }

  @Transactional
  public Account update(String shopId, String id, String name, Boolean active) {
    Account a = findByIdOrThrow(shopId, id);
    boolean isSystem = a.isSystem();
    if (StringUtils.hasText(name)) {
      if (isSystem && !a.getName().equals(name.trim())) {
        throw new ValidationException("System accounts cannot be renamed");
      }
      a.setName(name.trim());
    }
    if (active != null) {
      if (isSystem && !active) {
        throw new ValidationException("System accounts cannot be deactivated");
      }
      a.setActive(active);
    }
    a.setUpdatedAt(Instant.now());
    return accountRepository.save(a);
  }

  static NormalBalance defaultNormalBalance(AccountType type) {
    return switch (type) {
      case ASSET, EXPENSE -> NormalBalance.DEBIT;
      case LIABILITY, EQUITY, REVENUE -> NormalBalance.CREDIT;
    };
  }
}
