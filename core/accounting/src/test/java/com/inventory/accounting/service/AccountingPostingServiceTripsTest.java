package com.inventory.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.inventory.accounting.domain.model.LedgerEntry;
import com.inventory.accounting.domain.model.NormalBalance;
import com.inventory.accounting.domain.repository.JournalEntryRepository;
import com.inventory.accounting.domain.repository.LedgerEntryRepository;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.metrics.MetricsWrapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A posting resolves its accounts from the chart the seeding check already read, and moves each
 * account's balance once (atomically, in {@link AccountLedgerBalances}) by the entry's total for it.
 */
class AccountingPostingServiceTripsTest {

  private static final String SHOP = "shop-1";

  private JournalEntryRepository journals;
  private LedgerEntryRepository ledger;
  private AccountService accounts;
  private AccountingPostingService service;
  private FakeBalances balances;

  /** In-memory stand-in for the atomic per-account balance. */
  static final class FakeBalances extends AccountLedgerBalances {
    final Map<String, BigDecimal> balance = new java.util.HashMap<>();
    final List<String> calls = new ArrayList<>();

    FakeBalances() {
      super(null, null);
    }

    @Override
    public Moved add(String shopId, String accountId, BigDecimal delta) {
      calls.add("add " + accountId + " " + delta.stripTrailingZeros().toPlainString());
      return new Moved(balance.merge(accountId, delta, BigDecimal::add), java.time.Instant.parse("2026-10-22T10:00:00Z"));
    }

    @Override
    public void undo(String shopId, String accountId, BigDecimal delta) {
      calls.add("undo " + accountId + " " + delta.stripTrailingZeros().toPlainString());
      balance.merge(accountId, delta.negate(), BigDecimal::add);
    }
  }

  private final Account cash = account("a-cash", "1100", AccountType.ASSET, NormalBalance.DEBIT);
  private final Account sales = account("a-sales", "4000", AccountType.REVENUE, NormalBalance.CREDIT);
  private final Account gst = account("a-gst", "2210", AccountType.LIABILITY, NormalBalance.CREDIT);

  @BeforeEach
  void setUp() {
    journals = mock(JournalEntryRepository.class);
    ledger = mock(LedgerEntryRepository.class);
    accounts = mock(AccountService.class);
    JournalEntrySequenceService sequence = mock(JournalEntrySequenceService.class);
    when(sequence.nextEntryNo(SHOP)).thenReturn("JE-000001");
    when(journals.findByShopIdAndSourceTypeAndSourceId(any(), any(), any())).thenReturn(Optional.empty());
    when(journals.save(any(JournalEntry.class)))
        .thenAnswer(inv -> {
          JournalEntry e = inv.getArgument(0);
          e.setId("je-1");
          return e;
        });
    balances = new FakeBalances();
    service =
        new AccountingPostingService(journals, ledger, accounts, sequence, mock(MetricsWrapper.class), balances);
  }

  private static Account account(String id, String code, AccountType type, NormalBalance nb) {
    Account a = new Account();
    a.setId(id);
    a.setShopId(SHOP);
    a.setCode(code);
    a.setName("Account " + code);
    a.setType(type);
    a.setNormalBalance(nb);
    a.setActive(true);
    return a;
  }

  private static PostJournalLine line(String code, String debit, String credit) {
    PostJournalLine l = new PostJournalLine();
    l.setAccountCode(code);
    l.setDebit(debit != null ? new BigDecimal(debit) : null);
    l.setCredit(credit != null ? new BigDecimal(credit) : null);
    return l;
  }

  private static PostJournalRequest sale(PostJournalLine... lines) {
    PostJournalRequest r = new PostJournalRequest();
    r.setSourceType(JournalSource.SALE);
    r.setSourceId("purchase-1");
    r.setLines(List.of(lines));
    return r;
  }

  @SuppressWarnings("unchecked")
  private Map<String, LedgerEntry> savedRowsByAccount() {
    ArgumentCaptor<List<LedgerEntry>> rows = ArgumentCaptor.forClass(List.class);
    verify(ledger).saveAll(rows.capture());
    return rows.getValue().stream().collect(Collectors.toMap(LedgerEntry::getAccountId, Function.identity()));
  }

  @Test
  void accountsComeFromTheSeededChartAndBalancesFromTheAccount() {
    when(accounts.ensureSeededAndList(SHOP)).thenReturn(List.of(cash, sales, gst));
    balances.balance.put("a-cash", new BigDecimal("1000.0000"));
    balances.balance.put("a-sales", new BigDecimal("5000.0000"));

    service.post(SHOP, "user-1", sale(
        line("1100", "118", null),
        line("4000", null, "100"),
        line("2210", null, "18")));

    verify(accounts, times(1)).ensureSeededAndList(SHOP);
    verify(accounts, never()).ensureSeeded(anyString());
    verify(accounts, never()).findByCodeOrThrow(anyString(), anyString());
    verify(accounts, never()).findByIdOrThrow(anyString(), anyString());
    verify(ledger, never()).findByShopIdAndAccountIdOrderByPostedAtAsc(any(), any(), any());
    verify(ledger, never()).findFirst1ByShopIdAndAccountIdOrderByPostedAtDesc(any(), any());

    Map<String, LedgerEntry> rows = savedRowsByAccount();
    assertThat(rows.get("a-cash").getBalanceAfter()).isEqualByComparingTo("1118");  // Dr on a debit account
    assertThat(rows.get("a-sales").getBalanceAfter()).isEqualByComparingTo("5100"); // Cr on a credit account
    assertThat(rows.get("a-gst").getBalanceAfter()).isEqualByComparingTo("18");     // first row: from zero
    assertThat(rows.get("a-sales").getAccountCode()).isEqualTo("4000");
    // the row's time is when its account's balance moved
    assertThat(rows.get("a-cash").getPostedAt()).isEqualTo(java.time.Instant.parse("2026-10-22T10:00:00Z"));
  }

  @Test
  void sameAccountTwiceInOneEntryRunsOnFromItsOwnLine() {
    when(accounts.ensureSeededAndList(SHOP)).thenReturn(List.of(cash, sales));
    balances.balance.put("a-sales", new BigDecimal("10.0000"));

    service.post(SHOP, "user-1", sale(
        line("1100", "30", null),
        line("4000", null, "20"),
        line("4000", null, "10")));

    // one atomic move per account, by the entry's total for it
    assertThat(balances.calls).containsExactly("add a-cash 30", "add a-sales 30");
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<LedgerEntry>> rows = ArgumentCaptor.forClass(List.class);
    verify(ledger).saveAll(rows.capture());
    List<BigDecimal> salesBalances = new ArrayList<>();
    for (LedgerEntry r : rows.getValue()) {
      if ("a-sales".equals(r.getAccountId())) salesBalances.add(r.getBalanceAfter());
    }
    assertThat(salesBalances).usingElementComparator(BigDecimal::compareTo)
        .containsExactly(new BigDecimal("30"), new BigDecimal("40"));
  }

  @Test
  void ledgerRowsThatCannotBeSavedTakeTheirBalancesBack() {
    when(accounts.ensureSeededAndList(SHOP)).thenReturn(List.of(cash, sales));
    balances.balance.put("a-cash", new BigDecimal("100"));
    when(ledger.saveAll(any())).thenThrow(new IllegalStateException("write failed"));

    assertThatThrownBy(() -> service.post(SHOP, "user-1", sale(
        line("1100", "10", null),
        line("4000", null, "10"))))
        .isInstanceOf(IllegalStateException.class);

    assertThat(balances.balance.get("a-cash")).isEqualByComparingTo("100");
    assertThat(balances.balance.get("a-sales")).isEqualByComparingTo("0");
    assertThat(balances.calls).containsExactly("add a-cash 10", "add a-sales 10", "undo a-cash 10", "undo a-sales 10");
  }

  @Test
  void codeNotInTheChartStillFailsTheWayItDid() {
    when(accounts.ensureSeededAndList(SHOP)).thenReturn(List.of(cash));
    when(accounts.findByCodeOrThrow(SHOP, "9999"))
        .thenThrow(new ResourceNotFoundException("Account", "code", "9999"));

    assertThatThrownBy(() -> service.post(SHOP, "user-1", sale(
        line("1100", "10", null),
        line("9999", null, "10"))))
        .isInstanceOf(ResourceNotFoundException.class);
    verify(ledger, never()).saveAll(any());
  }

  @Test
  void duplicatedCodeInTheChartIsLeftToTheDatabaseLookup() {
    Account salesCopy = account("a-sales-2", "4000", AccountType.REVENUE, NormalBalance.CREDIT);
    when(accounts.ensureSeededAndList(SHOP)).thenReturn(List.of(cash, sales, salesCopy));
    when(accounts.findByCodeOrThrow(SHOP, "4000")).thenReturn(sales);

    service.post(SHOP, "user-1", sale(line("1100", "10", null), line("4000", null, "10")));

    verify(accounts, times(1)).findByCodeOrThrow(SHOP, "4000");
    verify(accounts, never()).findByCodeOrThrow(SHOP, "1100");
  }

  @Test
  void accountIdOutsideTheChartIsCheckedAgainstTheShop() {
    when(accounts.ensureSeededAndList(SHOP)).thenReturn(List.of(cash));
    Account other = account("a-other", "4000", AccountType.REVENUE, NormalBalance.CREDIT);
    when(accounts.findByIdOrThrow(SHOP, "a-other")).thenReturn(other);
    PostJournalLine byId = new PostJournalLine();
    byId.setAccountId("a-other");
    byId.setCredit(new BigDecimal("10"));

    service.post(SHOP, "user-1", sale(line("1100", "10", null), byId));

    verify(accounts, times(1)).findByIdOrThrow(SHOP, "a-other");
  }

  @Test
  void alreadyPostedSaleReturnsTheExistingEntryWithoutWriting() {
    when(accounts.ensureSeededAndList(SHOP)).thenReturn(List.of(cash, sales));
    JournalEntry existing = new JournalEntry();
    existing.setId("je-old");
    when(journals.findByShopIdAndSourceTypeAndSourceId(SHOP, JournalSource.SALE, "purchase-1"))
        .thenReturn(Optional.of(existing));

    JournalEntry out = service.post(SHOP, "user-1", sale(line("1100", "10", null), line("4000", null, "10")));

    assertThat(out.getId()).isEqualTo("je-old");
    verify(journals, never()).save(any());
    verify(ledger, never()).saveAll(any());
  }
}
