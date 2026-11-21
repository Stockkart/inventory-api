package com.inventory.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.accounting.domain.model.Account;
import com.inventory.accounting.domain.model.AccountType;
import com.inventory.accounting.domain.model.NormalBalance;
import com.inventory.accounting.api.SystemAccountCode;
import com.inventory.accounting.domain.repository.AccountRepository;
import com.inventory.accounting.domain.repository.LedgerEntryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Seeding hands back the chart it read (plus what it inserted) from the one query. */
class ChartOfAccountsSeederListTest {

  private static final String SHOP = "shop-1";

  private AccountRepository repo;
  private ChartOfAccountsSeeder seeder;

  @BeforeEach
  void setUp() {
    repo = mock(AccountRepository.class);
    seeder = new ChartOfAccountsSeeder(repo, mock(LedgerEntryRepository.class));
  }

  private List<Account> fullChart() {
    List<Account> all = new ArrayList<>();
    int i = 0;
    for (String code : seeder.canonicalCodesOrdered()) {
      Account a = new Account();
      a.setId("id-" + i++);
      a.setShopId(SHOP);
      a.setCode(code);
      all.add(a);
    }
    return all;
  }

  @Test
  void seededShopReturnsItsChartFromOneReadAndInsertsNothing() {
    List<Account> chart = fullChart();
    when(repo.findByShopIdOrderByCodeAsc(SHOP)).thenReturn(chart);

    List<Account> out = seeder.seedShopAndList(SHOP);

    assertThat(out).containsExactlyElementsOf(chart);
    verify(repo, times(1)).findByShopIdOrderByCodeAsc(SHOP);
    verify(repo, never()).saveAll(anyList());
  }

  @Test
  @SuppressWarnings("unchecked")
  void newShopReturnsTheInsertedAccounts() {
    when(repo.findByShopIdOrderByCodeAsc(SHOP)).thenReturn(List.of());
    when(repo.saveAll(anyList()))
        .thenAnswer(inv -> {
          List<Account> in = inv.getArgument(0);
          int i = 0;
          for (Account a : in) a.setId("new-" + i++);
          return in;
        });

    List<Account> out = seeder.seedShopAndList(SHOP);

    assertThat(out).extracting(Account::getCode)
        .containsExactlyElementsOf(seeder.canonicalCodesOrdered());
    assertThat(out).allSatisfy(a -> assertThat(a.getId()).startsWith("new-"));
    assertThat(seeder.seedShop("shop-2")).isEqualTo(seeder.canonicalCodesOrdered().size());
  }

  @Test
  void seedShopStillReportsZeroForASeededShop() {
    when(repo.findByShopIdOrderByCodeAsc(SHOP)).thenReturn(fullChart());
    assertThat(seeder.seedShop(SHOP)).isZero();
  }

  @Test
  void theHrAccountsAreSeededWithTheirTypesAndNormalBalances() {
    assertThat(seeder.canonicalCodes()).contains(
        SystemAccountCode.STAFF_ADVANCES, SystemAccountCode.SALARY_PAYABLE,
        SystemAccountCode.SALARY_EXPENSE, SystemAccountCode.ROUND_OFF_EXPENSE);
    when(repo.findByShopIdOrderByCodeAsc(SHOP)).thenReturn(List.of());
    when(repo.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

    List<Account> out = seeder.seedShopAndList(SHOP);

    assertHrAccount(out, SystemAccountCode.STAFF_ADVANCES, "Staff Advances", AccountType.ASSET,
        NormalBalance.DEBIT);
    assertHrAccount(out, SystemAccountCode.SALARY_PAYABLE, "Salary Payable",
        AccountType.LIABILITY, NormalBalance.CREDIT);
    assertHrAccount(out, SystemAccountCode.SALARY_EXPENSE, "Salaries and Wages",
        AccountType.EXPENSE, NormalBalance.DEBIT);
  }

  @Test
  @SuppressWarnings("unchecked")
  void aShopSeededBeforeHrGetsOnlyTheThreeNewAccounts() {
    Set<String> hrCodes = Set.of(SystemAccountCode.STAFF_ADVANCES,
        SystemAccountCode.SALARY_PAYABLE, SystemAccountCode.SALARY_EXPENSE);
    List<Account> before = fullChart().stream().filter(a -> !hrCodes.contains(a.getCode()))
        .toList();
    when(repo.findByShopIdOrderByCodeAsc(SHOP)).thenReturn(before);
    when(repo.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

    assertThat(seeder.seedShop(SHOP)).isEqualTo(3);

    ArgumentCaptor<List<Account>> saved = ArgumentCaptor.forClass(List.class);
    verify(repo).saveAll(saved.capture());
    assertThat(saved.getValue()).extracting(Account::getCode)
        .containsExactlyInAnyOrderElementsOf(hrCodes);
    assertThat(saved.getValue()).allSatisfy(a -> assertThat(a.isSystem()).isTrue());
  }

  private static void assertHrAccount(List<Account> accounts, String code, String name,
      AccountType type, NormalBalance normal) {
    Account a = accounts.stream().filter(x -> code.equals(x.getCode())).findFirst().orElseThrow();
    assertThat(a.getName()).isEqualTo(name);
    assertThat(a.getType()).isEqualTo(type);
    assertThat(a.getNormalBalance()).isEqualTo(normal);
    assertThat(a.isSystem()).isTrue();
  }
}
