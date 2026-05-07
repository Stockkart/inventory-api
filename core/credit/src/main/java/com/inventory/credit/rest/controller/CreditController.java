package com.inventory.credit.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.credit.rest.dto.request.CreateCreditChargeRequest;
import com.inventory.credit.rest.dto.request.CreateCreditSettlementRequest;
import com.inventory.credit.rest.dto.response.CreditAccountResponse;
import com.inventory.credit.rest.dto.response.CreditEntriesPageResponse;
import com.inventory.credit.service.CreditMapper;
import com.inventory.credit.service.CreditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/credit")
@RequiredArgsConstructor
public class CreditController {

  private final CreditService creditService;

  @PostMapping("/charge")
  public ResponseEntity<ApiResponse<?>> createCharge(
      @Valid @RequestBody CreateCreditChargeRequest body, HttpServletRequest request) {
    String shopId = resolveShop(request);
    String userId = (String) request.getAttribute("userId");
    var row = creditService.createCharge(shopId, userId, body);
    return ResponseEntity.ok(ApiResponse.success(CreditMapper.toResponse(row)));
  }

  @PostMapping("/settlement")
  public ResponseEntity<ApiResponse<?>> createSettlement(
      @Valid @RequestBody CreateCreditSettlementRequest body, HttpServletRequest request) {
    String shopId = resolveShop(request);
    String userId = (String) request.getAttribute("userId");
    var row = creditService.createSettlement(shopId, userId, body);
    return ResponseEntity.ok(ApiResponse.success(CreditMapper.toResponse(row)));
  }

  @GetMapping("/accounts")
  public ResponseEntity<ApiResponse<List<CreditAccountResponse>>> listAccounts(
      HttpServletRequest request) {
    String shopId = resolveShop(request);
    var rows = creditService.listAccountResponses(shopId);
    return ResponseEntity.ok(ApiResponse.success(rows));
  }

  @GetMapping("/accounts/{accountId}/entries")
  public ResponseEntity<ApiResponse<CreditEntriesPageResponse>> listEntries(
      @PathVariable String accountId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      HttpServletRequest request) {
    String shopId = resolveShop(request);
    return ResponseEntity.ok(ApiResponse.success(creditService.listEntries(shopId, accountId, page, size)));
  }

  private static String resolveShop(HttpServletRequest request) {
    String shopId = (String) request.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop not authenticated");
    }
    return shopId;
  }
}
