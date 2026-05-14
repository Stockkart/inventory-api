package com.inventory.accounting.rest.controller;

import com.inventory.accounting.api.AccountingFacade;
import com.inventory.accounting.api.PostJournalLine;
import com.inventory.accounting.api.PostJournalRequest;
import com.inventory.accounting.domain.model.Account;
import com.inventory.accounting.domain.model.JournalEntry;
import com.inventory.accounting.domain.model.JournalSource;
import com.inventory.accounting.domain.model.LedgerEntry;
import com.inventory.accounting.domain.model.PartyType;
import com.inventory.accounting.rest.dto.request.CreateAccountRequest;
import com.inventory.accounting.rest.dto.request.CreateJournalEntryRequest;
import com.inventory.accounting.rest.dto.request.ReverseJournalRequest;
import com.inventory.accounting.rest.dto.request.UpdateAccountRequest;
import com.inventory.accounting.rest.dto.response.AccountResponse;
import com.inventory.accounting.rest.dto.response.JournalEntriesPageResponse;
import com.inventory.accounting.rest.dto.response.JournalEntryResponse;
import com.inventory.accounting.rest.dto.response.LedgerEntryResponse;
import com.inventory.accounting.rest.dto.response.LedgerPageResponse;
import com.inventory.accounting.rest.dto.response.PartyStatementResponse;
import com.inventory.accounting.rest.dto.response.PartySummariesResponse;
import com.inventory.accounting.rest.dto.response.TrialBalanceResponse;
import com.inventory.accounting.service.AccountService;
import com.inventory.accounting.service.AccountingBackfillService;
import com.inventory.accounting.service.AccountingMapper;
import com.inventory.accounting.service.JournalQueryService;
import com.inventory.accounting.service.LedgerService;
import com.inventory.accounting.service.PartyLedgerService;
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
public class AccountingController {

  private final AccountService accountService;
  private final JournalQueryService journalQueryService;
  private final LedgerService ledgerService;
  private final PartyLedgerService partyLedgerService;
  private final TrialBalanceService trialBalanceService;
  private final AccountingFacade accountingFacade;
  private final AccountingBackfillService backfillService;

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

    PostJournalRequest req = new PostJournalRequest();
    req.setSourceType(JournalSource.MANUAL);
    req.setTxnDate(body.getTxnDate());
    req.setNarration(body.getNarration());
    req.setLines(
        body.getLines().stream()
            .map(
                in -> {
                  PostJournalLine l = new PostJournalLine();
                  l.setAccountCode(in.getAccountCode());
                  l.setAccountId(in.getAccountId());
                  l.setDebit(in.getDebit());
                  l.setCredit(in.getCredit());
                  l.setPartyType(in.getPartyType());
                  l.setPartyRefId(in.getPartyRefId());
                  l.setPartyDisplayName(in.getPartyDisplayName());
                  l.setMemo(in.getMemo());
                  return l;
                })
            .toList());

    JournalEntry posted = accountingFacade.post(shopId, userId, req);
    return ResponseEntity.ok(ApiResponse.success(AccountingMapper.toResponse(posted)));
  }

  @PostMapping("/journal-entries/{id}/reverse")
  public ResponseEntity<ApiResponse<JournalEntryResponse>> reverseJournal(
      @PathVariable String id,
      @Valid @RequestBody(required = false) ReverseJournalRequest body,
      HttpServletRequest request) {
    String shopId = resolveShop(request);
    String userId = (String) request.getAttribute("userId");
    String reason = body != null ? body.getReason() : null;
    JournalEntry reversal = accountingFacade.reverse(shopId, userId, id, reason);
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
