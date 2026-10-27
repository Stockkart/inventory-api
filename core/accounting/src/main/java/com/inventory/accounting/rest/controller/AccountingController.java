package com.inventory.accounting.rest.controller;


import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.accounting.api.PostJournalLine;
import com.inventory.accounting.api.PostJournalRequest;
import com.inventory.accounting.domain.model.Account;
import com.inventory.accounting.domain.model.JournalEntry;
import com.inventory.accounting.api.JournalSource;
import com.inventory.accounting.domain.model.LedgerEntry;
import com.inventory.accounting.api.PartyType;
import com.inventory.accounting.rest.dto.request.CreateAccountRequest;
import com.inventory.accounting.rest.dto.request.CreateJournalEntryRequest;
import com.inventory.accounting.rest.dto.request.OpeningBalanceRequest;
import com.inventory.accounting.rest.dto.request.ReverseJournalRequest;
import com.inventory.accounting.rest.dto.request.UpdateAccountRequest;
import com.inventory.accounting.rest.dto.response.AccountResponse;
import com.inventory.accounting.rest.dto.response.BalanceSheetResponse;
import com.inventory.accounting.rest.dto.response.ProfitAndLossResponse;
import com.inventory.accounting.rest.dto.response.JournalEntriesPageResponse;
import com.inventory.accounting.rest.dto.response.JournalEntryResponse;
import com.inventory.accounting.rest.dto.response.LedgerEntryResponse;
import com.inventory.accounting.rest.dto.response.LedgerPageResponse;
import com.inventory.accounting.rest.dto.response.PartyStatementResponse;
import com.inventory.accounting.rest.dto.response.PartySummariesResponse;
import com.inventory.accounting.rest.dto.response.TrialBalanceResponse;
import com.inventory.accounting.service.AccountingFacadeImpl;
import com.inventory.accounting.service.AccountService;
import com.inventory.accounting.service.AccountingBackfillService;
import com.inventory.accounting.service.AccountingMapper;
import com.inventory.accounting.service.JournalQueryService;
import com.inventory.accounting.service.LedgerService;
import com.inventory.accounting.service.PartyLedgerService;
import com.inventory.accounting.service.FinancialReportsService;
import com.inventory.accounting.service.JournalRequestMapper;
import com.inventory.accounting.service.TrialBalanceService;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public REST surface for the accounting module. All endpoints are shop-scoped. */
@RestController
@RequestMapping("/api/v1/accounting")
@RequiredArgsConstructor
@Latency(module = "accounting")
@RecordRequestRate(module = "accounting")
@RecordStatusCodes(module = "accounting")
public class AccountingController {

  private final AccountService accountService;
  private final JournalQueryService journalQueryService;
  private final LedgerService ledgerService;
  private final PartyLedgerService partyLedgerService;
  private final TrialBalanceService trialBalanceService;
  // The concrete facade: this controller returns stored entries, which the api does not expose.
  private final AccountingFacadeImpl accountingFacade;
  private final AccountingBackfillService backfillService;
  private final FinancialReportsService financialReportsService;

  // ---------------------------------------------------------------------------------------------
  // Chart of Accounts
  // ---------------------------------------------------------------------------------------------

  @GetMapping("/accounts")
  public ResponseEntity<ApiResponse<List<AccountResponse>>> listAccounts(
      HttpServletRequest request) {
    String shopId = resolveShop(request);
    List<AccountResponse> rows =
        accountService.list(shopId).stream().map(AccountingMapper::toResponse).toList();
    return ResponseEntity.ok(ApiResponse.success(rows));
  }

  @PostMapping("/accounts")
  public ResponseEntity<ApiResponse<AccountResponse>> createAccount(
      @Valid @RequestBody CreateAccountRequest body, HttpServletRequest request) {
    String shopId = resolveShop(request);
    Account a =
        accountService.create(
            shopId, body.getCode(), body.getName(), body.getType(), body.getNormalBalance());
    return ResponseEntity.ok(ApiResponse.success(AccountingMapper.toResponse(a)));
  }

  @PatchMapping("/accounts/{id}")
  public ResponseEntity<ApiResponse<AccountResponse>> updateAccount(
      @PathVariable String id,
      @Valid @RequestBody UpdateAccountRequest body,
      HttpServletRequest request) {
    String shopId = resolveShop(request);
    Account a = accountService.update(shopId, id, body.getName(), body.getActive());
    return ResponseEntity.ok(ApiResponse.success(AccountingMapper.toResponse(a)));
  }

  // ---------------------------------------------------------------------------------------------
  // Journal entries
  // ---------------------------------------------------------------------------------------------

  @GetMapping("/journal-entries")
  public ResponseEntity<ApiResponse<JournalEntriesPageResponse>> listJournal(
      @RequestParam(required = false) JournalSource sourceType,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      HttpServletRequest request) {
    String shopId = resolveShop(request);
    Page<JournalEntry> p = journalQueryService.list(shopId, sourceType, from, to, page, size);
    List<JournalEntryResponse> entries =
        p.getContent().stream().map(AccountingMapper::toResponse).toList();
    JournalEntriesPageResponse out =
        new JournalEntriesPageResponse(
            entries, p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    return ResponseEntity.ok(ApiResponse.success(out));
  }

  @GetMapping("/journal-entries/{id}")
  public ResponseEntity<ApiResponse<JournalEntryResponse>> getJournal(
      @PathVariable String id, HttpServletRequest request) {
    String shopId = resolveShop(request);
    JournalEntry e = journalQueryService.getOrThrow(shopId, id);
    return ResponseEntity.ok(ApiResponse.success(AccountingMapper.toResponse(e)));
  }

  @PostMapping("/journal-entries")
  public ResponseEntity<ApiResponse<JournalEntryResponse>> createManualJournal(
      @Valid @RequestBody CreateJournalEntryRequest body, HttpServletRequest request) {
    String shopId = resolveShop(request);
    String userId = (String) request.getAttribute("userId");

    PostJournalRequest req = PostJournalRequest.builder()
        .sourceType(JournalSource.MANUAL)
        .txnDate(body.getTxnDate())
        .narration(body.getNarration())
        .lines(JournalRequestMapper.fromManual(body))
        .build();

    JournalEntry posted = accountingFacade.postEntry(shopId, userId, req);
    return ResponseEntity.ok(ApiResponse.success(AccountingMapper.toResponse(posted)));
  }

  @PostMapping("/opening-balances")
  public ResponseEntity<ApiResponse<JournalEntryResponse>> postOpeningBalance(
      @Valid @RequestBody OpeningBalanceRequest body, HttpServletRequest request) {
    String shopId = resolveShop(request);
    String userId = (String) request.getAttribute("userId");

    PostJournalRequest req = PostJournalRequest.builder()
        .sourceType(JournalSource.OPENING_BALANCE)
        .sourceId("opening")
        .sourceKey("OPENING_BALANCE:" + shopId)
        .txnDate(body.getTxnDate())
        .narration(
            body.getNarration() != null && !body.getNarration().isBlank()
                ? body.getNarration().trim()
                : "Opening balances")
        .lines(JournalRequestMapper.fromOpening(body))
        .build();

    JournalEntry posted = accountingFacade.postEntry(shopId, userId, req);
    return ResponseEntity.ok(ApiResponse.success(AccountingMapper.toResponse(posted)));
  }

  @GetMapping("/opening-balances/status")
  public ResponseEntity<ApiResponse<JournalEntryResponse>> openingBalanceStatus(
      HttpServletRequest request) {
    String shopId = resolveShop(request);
    return accountingFacade
        .findEntryBySource(shopId, JournalSource.OPENING_BALANCE, "opening")
        .map(e -> ResponseEntity.ok(ApiResponse.success(AccountingMapper.toResponse(e))))
        .orElse(ResponseEntity.ok(ApiResponse.success(null)));
  }

  @PostMapping("/journal-entries/{id}/reverse")
  public ResponseEntity<ApiResponse<JournalEntryResponse>> reverseJournal(
      @PathVariable String id,
      @Valid @RequestBody(required = false) ReverseJournalRequest body,
      HttpServletRequest request) {
    String shopId = resolveShop(request);
    String userId = (String) request.getAttribute("userId");
    String reason = body != null ? body.getReason() : null;
    JournalEntry reversal = accountingFacade.reverseEntry(shopId, userId, id, reason);
    return ResponseEntity.ok(ApiResponse.success(AccountingMapper.toResponse(reversal)));
  }

  // ---------------------------------------------------------------------------------------------
  // Ledger
  // ---------------------------------------------------------------------------------------------

  @GetMapping("/ledger/{accountId}")
  public ResponseEntity<ApiResponse<LedgerPageResponse>> ledger(
      @PathVariable String accountId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      HttpServletRequest request) {
    String shopId = resolveShop(request);
    LedgerService.Result r = ledgerService.list(shopId, accountId, from, to, page, size);
    Page<LedgerEntry> p = r.entries();
    LedgerPageResponse resp =
        new LedgerPageResponse(
            AccountingMapper.toResponse(r.account()),
            p.getContent().stream().map(AccountingMapper::toResponse).toList(),
            p.getNumber(),
            p.getSize(),
            p.getTotalElements(),
            p.getTotalPages());
    return ResponseEntity.ok(ApiResponse.success(resp));
  }

  // ---------------------------------------------------------------------------------------------
  // Parties (subsidiary ledger for vendors / customers — control account is Sundry Creditors /
  // Sundry Debtors in the GL; per-party detail is sliced here by partyType + partyRefId.)
  // ---------------------------------------------------------------------------------------------

  @GetMapping("/parties")
  public ResponseEntity<ApiResponse<PartySummariesResponse>> listParties(
      @RequestParam PartyType type,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      HttpServletRequest request) {
    String shopId = resolveShop(request);
    PartyLedgerService.PartySummary s = partyLedgerService.listParties(shopId, type, from, to);
    return ResponseEntity.ok(ApiResponse.success(AccountingMapper.toResponse(s)));
  }

  @GetMapping("/parties/{type}/{partyRefId}/statement")
  public ResponseEntity<ApiResponse<PartyStatementResponse>> partyStatement(
      @PathVariable PartyType type,
      @PathVariable String partyRefId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      HttpServletRequest request) {
    String shopId = resolveShop(request);
    PartyLedgerService.Statement st =
        partyLedgerService.statement(shopId, type, partyRefId, from, to, page, size);
    return ResponseEntity.ok(ApiResponse.success(AccountingMapper.toResponse(st)));
  }

  // ---------------------------------------------------------------------------------------------
  // Reports
  // ---------------------------------------------------------------------------------------------

  @GetMapping("/reports/trial-balance")
  public ResponseEntity<ApiResponse<TrialBalanceResponse>> trialBalance(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
      HttpServletRequest request) {
    String shopId = resolveShop(request);
    TrialBalanceService.TrialBalance tb = trialBalanceService.asOf(shopId, asOf);
    return ResponseEntity.ok(ApiResponse.success(AccountingMapper.toResponse(tb)));
  }

  @GetMapping("/reports/profit-and-loss")
  public ResponseEntity<ApiResponse<ProfitAndLossResponse>> profitAndLoss(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      HttpServletRequest request) {
    String shopId = resolveShop(request);
    ProfitAndLossResponse out =
        AccountingMapper.toResponse(financialReportsService.profitAndLoss(shopId, from, to));
    return ResponseEntity.ok(ApiResponse.success(out));
  }

  @GetMapping("/reports/balance-sheet")
  public ResponseEntity<ApiResponse<BalanceSheetResponse>> balanceSheet(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
      HttpServletRequest request) {
    String shopId = resolveShop(request);
    BalanceSheetResponse out =
        AccountingMapper.toResponse(financialReportsService.balanceSheet(shopId, asOf));
    return ResponseEntity.ok(ApiResponse.success(out));
  }

  // ---------------------------------------------------------------------------------------------
  // Admin
  // ---------------------------------------------------------------------------------------------

  @PostMapping("/admin/backfill")
  public ResponseEntity<ApiResponse<AccountingBackfillService.BackfillResult>> backfill(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(defaultValue = "false") boolean force,
      HttpServletRequest request) {
    String shopId = resolveShop(request);
    String userId = (String) request.getAttribute("userId");
    return ResponseEntity.ok(
        ApiResponse.success(backfillService.backfill(shopId, userId, from, to, force)));
  }

  private static String resolveShop(HttpServletRequest request) {
    String shopId = (String) request.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop not authenticated");
    }
    return shopId;
  }
}
