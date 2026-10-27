package com.inventory.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.accounting.api.PostJournalLine;
import com.inventory.accounting.api.PostJournalRequest;
import com.inventory.accounting.domain.model.Account;
import com.inventory.accounting.domain.model.AccountType;
import com.inventory.accounting.domain.model.JournalEntry;
import com.inventory.accounting.api.JournalSource;
import com.inventory.accounting.domain.model.NormalBalance;
import com.inventory.accounting.domain.repository.JournalEntryRepository;
import com.inventory.accounting.domain.repository.LedgerEntryRepository;
import com.inventory.metrics.MetricsWrapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The unique (shopId, sourceType, sourceId) index makes a concurrent second post of one source
 * fail on insert; the loser gets the winner's entry back and writes no ledger rows. Entries
 * without a source keep failing as before.
 */
class AccountingPostingServiceDuplicateTest {

  private static final String SHOP = "shop-1";

  private JournalEntryRepository journals;
  private LedgerEntryRepository ledger;
  private AccountingPostingService service;

  @BeforeEach
  void setUp() {
    journals = mock(JournalEntryRepository.class);
    ledger = mock(LedgerEntryRepository.class);
    AccountService accounts = mock(AccountService.class);
    when(accounts.ensureSeededAndList(SHOP)).thenReturn(List.of(
        account("a-cash", "1100", AccountType.ASSET, NormalBalance.DEBIT),
        account("a-sales", "4100", AccountType.REVENUE, NormalBalance.CREDIT)));
    JournalEntrySequenceService sequence = mock(JournalEntrySequenceService.class);
    when(sequence.nextEntryNo(SHOP)).thenReturn("JE-000002");
    when(journals.save(any(JournalEntry.class)))
        .thenThrow(new DuplicateKeyException("E11000 index: shop_source_unique"));
    service = new AccountingPostingService(journals, ledger, accounts, sequence,
        mock(MetricsWrapper.class), new AccountingPostingServiceTripsTest.FakeBalances());
  }

  private static Account account(String id, String code, AccountType type, NormalBalance nb) {
    Account a = new Account();
    a.setId(id);
    a.setShopId(SHOP);
    a.setCode(code);
    a.setType(type);
    a.setNormalBalance(nb);
    a.setActive(true);
    return a;
  }

  private static PostJournalRequest request(JournalSource type, String sourceId) {
    PostJournalLine dr = new PostJournalLine();
    dr.setAccountCode("1100");
    dr.setDebit(new BigDecimal("10"));
    PostJournalLine cr = new PostJournalLine();
    cr.setAccountCode("4100");
    cr.setCredit(new BigDecimal("10"));
    PostJournalRequest r = new PostJournalRequest();
    r.setSourceType(type);
    r.setSourceId(sourceId);
    r.setLines(List.of(dr, cr));
    return r;
  }

  @Test
  void aDuplicateSourceOnInsertReturnsTheEntryAlreadyPosted() {
    JournalEntry winner = new JournalEntry();
    winner.setId("je-winner");
    // empty on the pre-check (the race), the winner's entry on the re-read after the clash
    when(journals.findByShopIdAndSourceTypeAndSourceId(SHOP, JournalSource.PAYROLL_RUN, "run-1:0"))
        .thenReturn(Optional.empty(), Optional.of(winner));

    JournalEntry out = service.post(SHOP, "user-1", request(JournalSource.PAYROLL_RUN, "run-1:0"));

    assertThat(out.getId()).isEqualTo("je-winner");
    verify(ledger, never()).saveAll(any());
  }

  @Test
  void insideATransactionTheDuplicateEndsTheFlowWithoutAReRead() {
    when(journals.findByShopIdAndSourceTypeAndSourceId(SHOP, JournalSource.PAYROLL_RUN, "run-1:0"))
        .thenReturn(Optional.empty());
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> service.post(SHOP, "user-1", request(JournalSource.PAYROLL_RUN, "run-1:0")))
          .isInstanceOf(DuplicateKeyException.class);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    // only the pre-check: the aborted transaction is not read again
    verify(journals, times(1)).findByShopIdAndSourceTypeAndSourceId(SHOP, JournalSource.PAYROLL_RUN, "run-1:0");
    verify(ledger, never()).saveAll(any());
  }

  @Test
  void aDuplicateWithoutASourceIsStillAnError() {
    assertThatThrownBy(() -> service.post(SHOP, "user-1", request(JournalSource.MANUAL, null)))
        .isInstanceOf(DuplicateKeyException.class);
    verify(journals, never()).findByShopIdAndSourceTypeAndSourceId(any(), any(), any());
    verify(ledger, never()).saveAll(any());
  }
}
