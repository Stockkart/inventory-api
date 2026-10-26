package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.accounting.api.AccountingFacade;
import com.inventory.accounting.api.PostJournalLine;
import com.inventory.accounting.api.PostJournalRequest;
import com.inventory.accounting.domain.model.JournalEntry;
import com.inventory.accounting.domain.model.JournalSource;
import com.inventory.accounting.domain.model.JournalStatus;
import com.inventory.accounting.domain.model.PartyType;
import com.inventory.hr.domain.model.Advance;
import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.PayrollRun;
import com.inventory.hr.domain.model.enums.HrPaymentMethod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HrAccountingPosterTest {

  @Mock private AccountingFacade facade;
  @Mock private com.inventory.metrics.MetricsWrapper metrics;
  @InjectMocks private HrAccountingPoster poster;

  private static PayrollLine line(String nbr, String rounding, String net, String recovered) {
    PayrollLine l = new PayrollLine();
    l.setId("line-1");
    l.setPeriod("2025-04");
    l.setNetBeforeRounding(new BigDecimal(nbr));
    l.setRounding(new BigDecimal(rounding));
    l.setNet(new BigDecimal(net));
    l.setEmployeeSnapshot(new PayrollLine.EmployeeSnapshot("Ravi Kumar", "EMP-001", null, null,
        null, null, null, null));
    if (recovered != null) {
      l.setAdvanceRecoveries(List.of(new PayrollLine.AdvanceRecovery("a1",
          new BigDecimal(recovered))));
    }
    return l;
  }

  private static PayrollRun run(int revision) {
    PayrollRun run = new PayrollRun();
    run.setId("run-1");
    run.setPeriod("2025-04");
    run.setRevision(revision);
    return run;
  }

  private PostJournalRequest posted() {
    ArgumentCaptor<PostJournalRequest> req = ArgumentCaptor.forClass(PostJournalRequest.class);
    verify(facade).post(eq(SHOP), eq(USER), req.capture());
    return req.getValue();
  }

  private static BigDecimal amount(PostJournalRequest req, String code, boolean debit) {
    return req.getLines().stream().filter(l -> code.equals(l.getAccountCode()))
        .map(debit ? PostJournalLine::getDebit : PostJournalLine::getCredit)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  @Test
  void theRunJournalMatchesTheDesignTableWithRoundingOnRoundOffExpense() {
    when(facade.post(anyString(), anyString(), any())).thenReturn(new JournalEntry());

    poster.postPayrollRun(SHOP, USER, run(2), List.of(
        line("13145.83", "0.17", "13146.00", "1000.00"),
        line("999.60", "0.40", "1000.00", null)));

    PostJournalRequest req = posted();
    assertEquals(JournalSource.PAYROLL_RUN, req.getSourceType());
    assertEquals("run-1:2", req.getSourceId());
    assertEquals(LocalDate.of(2025, 4, 30), req.getTxnDate());
    assertEquals(0, new BigDecimal("15145.43").compareTo(amount(req, "5700", true)));
    assertEquals(0, new BigDecimal("0.57").compareTo(amount(req, "5500", true)));
    assertEquals(0, new BigDecimal("14146.00").compareTo(amount(req, "2400", false)));
    assertEquals(0, new BigDecimal("1000.00").compareTo(amount(req, "1250", false)));
    assertTrue(req.getLines().stream().allMatch(l -> l.getPartyType() == PartyType.SHOP
        && "Payroll 2025-04".equals(l.getMemo())));
  }

  @Test
  void netRoundedDownCreditsRoundOffExpense() {
    when(facade.post(anyString(), anyString(), any())).thenReturn(new JournalEntry());

    poster.postPayrollRun(SHOP, USER, run(0), List.of(line("100.40", "-0.40", "100.00", null)));

    PostJournalRequest req = posted();
    assertEquals(0, new BigDecimal("0.40").compareTo(amount(req, "5500", false)));
    assertEquals(0, amount(req, "5700", true).compareTo(
        amount(req, "2400", false).add(amount(req, "5500", false))));
  }

  @Test
  void aShopsOwnAccountOnAnHrCodeStopsThePostWithAPlainMessage() {
    when(facade.ownAccountCodes(eq(SHOP), any())).thenReturn(java.util.Set.of("2400"));

    com.inventory.common.exception.BaseException e = org.junit.jupiter.api.Assertions.assertThrows(
        com.inventory.common.exception.BaseException.class,
        () -> poster.postPayrollRun(SHOP, USER, run(0),
            List.of(line("100.40", "-0.40", "100.00", null))));

    assertEquals(com.inventory.common.constants.ErrorCode.HR_ACCOUNT_CONFLICT, e.getErrorCode());
    assertEquals("Account 2400 in your chart of accounts is your own account. Rename or move it"
        + " so payroll can use 2400.", e.getMessage());
    verify(facade, never()).post(anyString(), anyString(), any());
    verify(metrics).increment(eq("inventory_hr_account_conflicts_total"), eq(1.0),
        any(String[].class));
  }

  @Test
  void onlyTheHrCodesOfTheEntryAreCheckedInOneRead() {
    when(facade.post(anyString(), anyString(), any())).thenReturn(new JournalEntry());

    poster.postPayrollRun(SHOP, USER, run(0), List.of(line("100.40", "-0.40", "100.00", null)));

    verify(facade).ownAccountCodes(SHOP, new java.util.TreeSet<>(List.of("2400", "5700")));
  }

  @Test
  void aZeroRunPostsNothing() {
    assertNull(poster.postPayrollRun(SHOP, USER, run(0), List.of(line("0", "0", "0", null))));
    verify(facade, never()).post(anyString(), anyString(), any());
  }

  @Test
  void anUpiAdvanceDebitsStaffAdvancesAndCreditsBank() {
    when(facade.post(anyString(), anyString(), any())).thenReturn(new JournalEntry());
    Advance a = new Advance();
    a.setId("adv-1");
    a.setDate("2025-03-10");
    a.setAmount(new BigDecimal("3000"));
    a.setPaidBy(HrPaymentMethod.UPI);

    poster.postAdvance(SHOP, USER, a, "EMP-001");

    PostJournalRequest req = posted();
    assertEquals(JournalSource.STAFF_ADVANCE, req.getSourceType());
    assertEquals("adv-1", req.getSourceId());
    assertEquals(0, new BigDecimal("3000").compareTo(amount(req, "1250", true)));
    assertEquals(0, new BigDecimal("3000").compareTo(amount(req, "1110", false)));
    assertTrue(req.getLines().stream().allMatch(l -> "EMP-001".equals(l.getMemo())));
    assertTrue(req.getNarration().contains("EMP-001"));
  }

  @Test
  void cashGoesToCashInHand() {
    assertEquals("1100", HrAccountingPoster.cashOrBank(HrPaymentMethod.CASH));
    assertEquals("1100", HrAccountingPoster.cashOrBank(null));
    assertEquals("1110", HrAccountingPoster.cashOrBank(HrPaymentMethod.BANK));
  }

  @Test
  void aPaymentBatchIsOneEntryKeyedByTheBatchId() {
    when(facade.post(anyString(), anyString(), any())).thenReturn(new JournalEntry());

    poster.postPaymentBatch(SHOP, USER, "batch-1", List.of(
        line("100", "0", "100", null), line("250.40", "-0.40", "250", null)),
        HrPaymentMethod.CASH, LocalDate.of(2025, 5, 2));

    PostJournalRequest req = posted();
    assertEquals(JournalSource.SALARY_PAYMENT, req.getSourceType());
    assertEquals("batch-1", req.getSourceId());
    assertEquals(0, new BigDecimal("350").compareTo(amount(req, "2400", true)));
    assertEquals(0, new BigDecimal("350").compareTo(amount(req, "1100", false)));
  }

  @Test
  void anUndoIsACompensatingEntryForOneLine() {
    when(facade.post(anyString(), anyString(), any())).thenReturn(new JournalEntry());

    poster.postPaymentUndo(SHOP, USER, line("100", "0", "100", null), 3, HrPaymentMethod.BANK,
        LocalDate.of(2025, 5, 3));

    PostJournalRequest req = posted();
    assertEquals("undo:line-1:3", req.getSourceId());
    assertEquals(0, new BigDecimal("100").compareTo(amount(req, "1110", true)));
    assertEquals(0, new BigDecimal("100").compareTo(amount(req, "2400", false)));
    assertTrue(req.getLines().stream().allMatch(l -> "EMP-001".equals(l.getMemo())),
        "employee code only, never the name");
  }

  @Test
  void reverseSkipsAnEntryAlreadyReversedAndMissingSources() {
    JournalEntry reversed = new JournalEntry();
    reversed.setId("je-1");
    reversed.setStatus(JournalStatus.REVERSED);
    reversed.setReversedByEntryId("je-2");
    when(facade.findBySource(SHOP, JournalSource.PAYROLL_RUN, "run-1:0"))
        .thenReturn(Optional.of(reversed));
    when(facade.findBySource(SHOP, JournalSource.PAYROLL_RUN, "run-1:1"))
        .thenReturn(Optional.empty());

    assertEquals("je-2", poster.reverseBySource(SHOP, USER, JournalSource.PAYROLL_RUN,
        "run-1:0", "reopen"));
    assertNull(poster.reverseBySource(SHOP, USER, JournalSource.PAYROLL_RUN, "run-1:1",
        "reopen"));
    verify(facade, never()).reverse(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void reverseReversesALiveEntry() {
    JournalEntry live = new JournalEntry();
    live.setId("je-1");
    live.setStatus(JournalStatus.POSTED);
    JournalEntry reversal = new JournalEntry();
    reversal.setId("je-9");
    when(facade.findBySource(SHOP, JournalSource.STAFF_ADVANCE, "adv-1"))
        .thenReturn(Optional.of(live));
    when(facade.reverse(SHOP, USER, "je-1", "cancelled")).thenReturn(reversal);

    assertEquals("je-9", poster.reverseBySource(SHOP, USER, JournalSource.STAFF_ADVANCE,
        "adv-1", "cancelled"));
  }
}
