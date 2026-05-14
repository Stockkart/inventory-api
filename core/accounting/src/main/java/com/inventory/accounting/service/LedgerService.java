package com.inventory.accounting.service;

import com.inventory.accounting.domain.model.Account;
import com.inventory.accounting.domain.model.LedgerEntry;
import com.inventory.accounting.domain.repository.LedgerEntryRepository;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Per-account ledger reader with optional date filtering. */
@Service
@RequiredArgsConstructor
public class LedgerService {

  private final LedgerEntryRepository ledgerEntryRepository;
  private final AccountService accountService;

  @Transactional(readOnly = true)
  public Result list(
      String shopId, String accountId, LocalDate from, LocalDate to, int page, int size) {
    Account account = accountService.findByIdOrThrow(shopId, accountId);
    int safePage = Math.max(page, 0);
    int safeSize = Math.min(Math.max(size, 1), 200);
    PageRequest pr =
        PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.ASC, "postedAt"));
    Page<LedgerEntry> p;
    if (from != null && to != null) {
      p =
          ledgerEntryRepository
              .findByShopIdAndAccountIdAndTxnDateBetweenOrderByPostedAtAsc(
                  shopId, accountId, from, to, pr);
    } else {
      p = ledgerEntryRepository.findByShopIdAndAccountIdOrderByPostedAtAsc(shopId, accountId, pr);
    }
    return new Result(account, p);
  }

  /** View object returned by {@link #list(String, String, LocalDate, LocalDate, int, int)}. */
  public record Result(Account account, Page<LedgerEntry> entries) {}
}
