package com.inventory.user.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.user.domain.model.LedgerPartyType;
import com.inventory.user.rest.dto.ledger.BalanceResponse;
import com.inventory.user.rest.dto.ledger.CreateLedgerEntryRequest;
import com.inventory.user.rest.dto.ledger.LedgerEntriesResponse;
import com.inventory.user.rest.dto.ledger.LedgerEntryDto;
import com.inventory.user.rest.dto.ledger.CustomerReceivablesResponse;
import com.inventory.user.rest.dto.ledger.PayablesResponse;
import com.inventory.user.rest.dto.ledger.PayablesToShopsResponse;
import com.inventory.user.rest.dto.ledger.ReceivablesResponse;
import com.inventory.user.service.CreditLedgerService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ledger")
@Slf4j
public class CreditLedgerController {

  @Autowired
  private CreditLedgerService creditLedgerService;

  @PostMapping
  public ResponseEntity<ApiResponse<LedgerEntryDto>> createEntry(
      @RequestBody CreateLedgerEntryRequest request,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    String userId = (String) httpRequest.getAttribute("userId");
    if (!StringUtils.hasText(shopId) || !StringUtils.hasText(userId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
    LedgerEntryDto response = creditLedgerService.createEntry(request, shopId, userId);
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  @GetMapping("/balance")
  public ResponseEntity<ApiResponse<BalanceResponse>> getBalance(
      @RequestParam LedgerPartyType partyType,
      @RequestParam String partyId,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
    BalanceResponse response = creditLedgerService.getBalance(shopId, partyType, partyId);
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  @GetMapping("/receivables")
  public ResponseEntity<ApiResponse<ReceivablesResponse>> getReceivables(
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
    ReceivablesResponse response = creditLedgerService.getReceivables(shopId);
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  @GetMapping("/customer-receivables")
  public ResponseEntity<ApiResponse<CustomerReceivablesResponse>> getCustomerReceivables(
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
    CustomerReceivablesResponse response = creditLedgerService.getCustomerReceivables(shopId);
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  @GetMapping("/payables")
  public ResponseEntity<ApiResponse<PayablesResponse>> getPayables(
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
    PayablesResponse response = creditLedgerService.getPayables(shopId);
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  @GetMapping("/payables-to-shops")
  public ResponseEntity<ApiResponse<PayablesToShopsResponse>> getPayablesToShops(
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
    PayablesToShopsResponse response = creditLedgerService.getPayablesToShops(shopId);
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  @GetMapping("/entries")
  public ResponseEntity<ApiResponse<LedgerEntriesResponse>> listEntries(
      @RequestParam(required = false) LedgerPartyType partyType,
      @RequestParam(required = false) String partyId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
    LedgerEntriesResponse response = creditLedgerService.listEntries(shopId, partyType, partyId, page, size);
    return ResponseEntity.ok(ApiResponse.success(response));
  }
}
