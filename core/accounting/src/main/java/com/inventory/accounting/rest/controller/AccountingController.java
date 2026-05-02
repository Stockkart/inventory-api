package com.inventory.accounting.rest.controller;

import com.inventory.accounting.domain.model.PartyType;
import com.inventory.accounting.rest.dto.mapping.AccountingMapper;
import com.inventory.accounting.rest.dto.request.CreateGlAccountRequest;
import com.inventory.accounting.rest.dto.request.PostJournalLineRequest;
import com.inventory.accounting.rest.dto.request.PostJournalRequest;
import com.inventory.accounting.rest.dto.response.GlAccountResponse;
import com.inventory.accounting.rest.dto.response.AccountingShopSummaryDto;
import com.inventory.accounting.rest.dto.response.JournalEntryResponse;
import com.inventory.accounting.rest.dto.response.JournalListResponse;
import com.inventory.accounting.rest.dto.response.SubledgerEntriesPageResponse;
import com.inventory.accounting.rest.dto.response.SubledgerPartyBalanceResponse;
import com.inventory.accounting.service.AccountingReadService;
import com.inventory.accounting.service.GlAccountWriteService;
import com.inventory.accounting.service.GlBootstrapService;
import com.inventory.accounting.service.PostingService;
import com.inventory.accounting.service.PostingService.PostingLineDraft;
import com.inventory.accounting.service.SubledgerService;
import com.inventory.accounting.service.TrialBalanceQueryService;
import com.inventory.accounting.domain.model.GlAccount;
import com.inventory.accounting.domain.model.JournalEntry;
import com.inventory.accounting.domain.model.SubledgerEntry;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/accounting")
@Slf4j
public class AccountingController {

  @Autowired private GlBootstrapService glBootstrapService;

  @Autowired private AccountingReadService accountingReadService;

  @Autowired private PostingService postingService;

  @Autowired private SubledgerService subledgerService;

  @Autowired private TrialBalanceQueryService trialBalanceQueryService;

  @Autowired private GlAccountWriteService glAccountWriteService;

  /** Resolve effective shop filter and journal cardinality (troubleshooting / multi-shop drift). */
  @GetMapping("/shop-summary")
  public ResponseEntity<ApiResponse<AccountingShopSummaryDto>> shopSummary(HttpServletRequest httpRequest) {
    String shopId = resolveShop(httpRequest);
    long chart = accountingReadService.countChartAccounts(shopId);
    long journals = accountingReadService.countJournals(shopId);
    return ResponseEntity.ok(
        ApiResponse.success(new AccountingShopSummaryDto(shopId, chart, journals)));
  }

  /** Idempotent starter — seeds default nominal accounts used by postings. */
  @PostMapping("/chart/bootstrap")
  public ResponseEntity<ApiResponse<Void>> bootstrapChart(HttpServletRequest httpRequest) {
    String shopId = resolveShop(httpRequest);
    glBootstrapService.ensureDefaultsForShop(shopId);
    return ResponseEntity.ok(ApiResponse.success(null));
  }

  @GetMapping("/gl-accounts")
  public ResponseEntity<ApiResponse<List<GlAccountResponse>>> listAccounts(
      HttpServletRequest httpRequest) {
    String shopId = resolveShop(httpRequest);
    List<GlAccount> chart = accountingReadService.listAccounts(shopId);
    Map<String, TrialBalanceQueryService.DebitCreditTotals> totals =
        trialBalanceQueryService.totalsByAccountId(shopId);
    List<GlAccountResponse> dtos =
        chart.stream()
            .map(
                a ->
                    AccountingMapper.toResponse(
                        a, totals.getOrDefault(a.getId(), TrialBalanceQueryService.DebitCreditTotals.zero())))
            .collect(Collectors.toList());
    return ResponseEntity.ok(ApiResponse.success(dtos));
  }

  /** Adds a shop-specific nominal account ({@code systemAccount=false}); built-in codes are reserved. */
  @PostMapping("/gl-accounts")
  public ResponseEntity<ApiResponse<GlAccountResponse>> createManualAccount(
      @Valid @RequestBody CreateGlAccountRequest body, HttpServletRequest httpRequest) {
    String shopId = resolveShop(httpRequest);
    var saved = glAccountWriteService.createManualAccount(shopId, body);
    return ResponseEntity.ok(
        ApiResponse.success(
            AccountingMapper.toResponse(saved, TrialBalanceQueryService.DebitCreditTotals.zero())));
  }

  @PostMapping("/journals/manual")
  public ResponseEntity<ApiResponse<JournalEntryResponse>> postManualJournal(
      @Valid @RequestBody PostJournalRequest body, HttpServletRequest httpRequest) {
    String shopId = resolveShop(httpRequest);
    String userId = (String) httpRequest.getAttribute("userId");

    List<PostingLineDraft> drafts =
        body.getLines().stream().map(AccountingController::toDraft).collect(Collectors.toList());

    JournalEntry entry =
        postingService.postManualJournal(
            shopId,
            body.getJournalDate(),
            body.getDescription(),
            drafts,
            userId,
            body.getSourceKey());

    return ResponseEntity.ok(ApiResponse.success(AccountingMapper.toResponse(entry)));
  }

  @GetMapping("/journals")
  public ResponseEntity<ApiResponse<JournalListResponse>> listJournals(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      HttpServletRequest httpRequest) {
    String shopId = resolveShop(httpRequest);
    Page<JournalEntry> p = accountingReadService.pageJournals(shopId, page, size);
    List<JournalEntryResponse> items =
        p.getContent().stream().map(AccountingMapper::toResponse).collect(Collectors.toList());
    JournalListResponse wrap =
        new JournalListResponse(
            items, p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    return ResponseEntity.ok(ApiResponse.success(wrap));
  }

  @GetMapping("/journals/{journalId}")
  public ResponseEntity<ApiResponse<JournalEntryResponse>> getJournal(
      @PathVariable String journalId, HttpServletRequest httpRequest) {
    String shopId = resolveShop(httpRequest);
    JournalEntry j = accountingReadService.getJournal(shopId, journalId);
    return ResponseEntity.ok(ApiResponse.success(AccountingMapper.toResponse(j)));
  }

  @GetMapping("/reports/trial-balance")
  public ResponseEntity<ApiResponse<List<TrialBalanceQueryService.TrialBalanceLine>>> trialBalance(
      HttpServletRequest httpRequest) {
    String shopId = resolveShop(httpRequest);
    glBootstrapService.ensureSeedAccountsOnly(shopId);
    return ResponseEntity.ok(ApiResponse.success(trialBalanceQueryService.computeTrialBalance(shopId)));
  }

  @GetMapping("/subledger/balance")
  public ResponseEntity<ApiResponse<SubledgerPartyBalanceResponse>> subledgerPartyBalance(
      @RequestParam PartyType partyType,
      @RequestParam String partyId,
      HttpServletRequest httpRequest) {
    String shopId = resolveShop(httpRequest);
    var balance = subledgerService.partyBalance(shopId, partyType, partyId);
    String hint =
        partyType == PartyType.VENDOR
            ? "Vendor: positive = you owe supplier; negative = advance / overpayment credit."
            : "Customer: positive = they owe you; negative = unapplied credit/advance.";
    SubledgerPartyBalanceResponse dto =
        new SubledgerPartyBalanceResponse(shopId, partyType, partyId, balance, hint);
    return ResponseEntity.ok(ApiResponse.success(dto));
  }

  @GetMapping("/subledger/entries")
  public ResponseEntity<ApiResponse<SubledgerEntriesPageResponse>> listSubledger(
      @RequestParam(required = false) PartyType partyType,
      @RequestParam(required = false) String partyId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      HttpServletRequest httpRequest) {
    String shopId = resolveShop(httpRequest);
    Page<SubledgerEntry> p = subledgerService.listEntries(shopId, partyType, partyId, page, size);
    List<SubledgerEntriesPageResponse.EntryRow> rows =
        p.getContent().stream().map(AccountingMapper::toRow).collect(Collectors.toList());
    SubledgerEntriesPageResponse wrap =
        new SubledgerEntriesPageResponse(
            rows, p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    return ResponseEntity.ok(ApiResponse.success(wrap));
  }

  private static PostingLineDraft toDraft(PostJournalLineRequest l) {
    return new PostingLineDraft(
        l.getAccountCode(), l.getDebit(), l.getCredit(), l.getMemo(), l.getPartyType(), l.getPartyId());
  }

  private static String resolveShop(HttpServletRequest request) {
    String shopId = (String) request.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop not authenticated");
    }
    return shopId;
  }
}
