package com.inventory.product.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.product.rest.dto.request.AddToCartRequest;
import com.inventory.product.rest.dto.request.CreateEstimateRequest;
import com.inventory.product.rest.dto.request.CreateQuotationRequest;
import com.inventory.product.rest.dto.request.UpdatePurchaseStatusRequest;
import com.inventory.product.rest.dto.response.AddToCartResponse;
import com.inventory.product.rest.dto.response.CheckoutResponse;
import com.inventory.product.rest.dto.response.ConvertEstimateResponse;
import com.inventory.product.rest.dto.response.CustomerProductHistoryResponse;
import com.inventory.product.rest.dto.response.EstimateListResponse;
import com.inventory.product.rest.dto.response.PurchaseListResponse;
import com.inventory.product.rest.dto.response.QuotationListResponse;
import com.inventory.product.domain.model.enums.EstimateState;
import com.inventory.product.service.CheckoutService;
import com.inventory.product.service.CustomerProductHistoryService;
import com.inventory.product.service.EstimateService;
import com.inventory.product.service.QuotationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Latency(module = "product")
@RecordRequestRate(module = "product")
@RecordStatusCodes(module = "product")
public class CheckoutController {

  @Autowired
  private CheckoutService checkoutService;

  @Autowired
  private CustomerProductHistoryService customerProductHistoryService;

  @Autowired
  private QuotationService quotationService;

  @Autowired
  private EstimateService estimateService;

  @GetMapping("/cart")
  public ResponseEntity<ApiResponse<AddToCartResponse>> getCart(
      @RequestParam(required = false) String purchaseId,
      HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(checkoutService.getCart(httpRequest, purchaseId)));
  }

  @GetMapping("/cart/quotations")
  public ResponseEntity<ApiResponse<QuotationListResponse>> listQuotations(
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    String userId = (String) httpRequest.getAttribute("userId");
    return ResponseEntity.ok(
        ApiResponse.success(quotationService.listOpenQuotations(userId, shopId)));
  }

  @PostMapping("/cart/quotations")
  public ResponseEntity<ApiResponse<AddToCartResponse>> createQuotation(
      @RequestBody CreateQuotationRequest request,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    String userId = (String) httpRequest.getAttribute("userId");
    return ResponseEntity.ok(
        ApiResponse.success(quotationService.createQuotation(request, userId, shopId)));
  }

  @DeleteMapping("/cart/quotations/{purchaseId}")
  public ResponseEntity<ApiResponse<Void>> cancelQuotation(
      @PathVariable String purchaseId,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    String userId = (String) httpRequest.getAttribute("userId");
    quotationService.cancelQuotation(purchaseId, userId, shopId);
    return ResponseEntity.ok(ApiResponse.success(null));
  }

  @GetMapping("/estimates")
  public ResponseEntity<ApiResponse<EstimateListResponse>> listEstimates(
      @RequestParam(required = false) EstimateState state,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    return ResponseEntity.ok(
        ApiResponse.success(estimateService.listEstimates(shopId, state, q, page, size)));
  }

  @PostMapping("/estimates")
  public ResponseEntity<ApiResponse<AddToCartResponse>> createEstimate(
      @RequestBody CreateEstimateRequest request, HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    String userId = (String) httpRequest.getAttribute("userId");
    return ResponseEntity.ok(
        ApiResponse.success(estimateService.createEstimate(request, userId, shopId)));
  }

  @GetMapping("/estimates/{purchaseId}")
  public ResponseEntity<ApiResponse<AddToCartResponse>> getEstimate(
      @PathVariable String purchaseId, HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    return ResponseEntity.ok(ApiResponse.success(estimateService.getEstimate(purchaseId, shopId)));
  }

  @PostMapping("/estimates/{purchaseId}/convert")
  public ResponseEntity<ApiResponse<ConvertEstimateResponse>> convertEstimate(
      @PathVariable String purchaseId, HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    String userId = (String) httpRequest.getAttribute("userId");
    return ResponseEntity.ok(
        ApiResponse.success(estimateService.convertToSale(purchaseId, userId, shopId)));
  }

  @DeleteMapping("/estimates/{purchaseId}")
  public ResponseEntity<ApiResponse<Void>> discardEstimate(
      @PathVariable String purchaseId, HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    String userId = (String) httpRequest.getAttribute("userId");
    estimateService.discardEstimate(purchaseId, userId, shopId);
    return ResponseEntity.ok(ApiResponse.success(null));
  }

  @PostMapping("/cart/upsert")
  public ResponseEntity<ApiResponse<AddToCartResponse>> addToCart(@RequestBody AddToCartRequest request,
                                                                  HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(checkoutService.addToCart(request, httpRequest)));
  }

  @PutMapping("/cart/status")
  public ResponseEntity<ApiResponse<CheckoutResponse>> updatePurchaseStatus(@RequestBody UpdatePurchaseStatusRequest request,
                                                                            HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(checkoutService.updatePurchaseStatus(request, httpRequest)));
  }

  @GetMapping("/purchases")
  public ResponseEntity<ApiResponse<PurchaseListResponse>> getPurchases(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String order,
      HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(checkoutService.getPurchases(page, limit, order, httpRequest)));
  }

  /**
   * Search sales by invoice number, date range and customer, paginated.
   *
   * @param page page number (1-based, optional, default: 1)
   * @param limit page size (optional, default: 20, max: 100)
   * @param invoiceNo optional invoice number, matched as a substring
   * @param from optional inclusive first sale date (yyyy-MM-dd)
   * @param to optional inclusive last sale date (yyyy-MM-dd)
   * @param customer optional free text matched against name, phone, email or address
   * @param httpRequest HTTP request containing shopId
   * @return list of purchases with pagination
   */
  @GetMapping("/purchases/search")
  public ResponseEntity<ApiResponse<PurchaseListResponse>> searchPurchases(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String invoiceNo,
      @RequestParam(required = false)
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false)
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String customer,
      HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(
        checkoutService.searchPurchases(page, limit, invoiceNo, from, to,
            customer, httpRequest)));
  }

  /**
   * Prior completed sales of specific products to a customer (batched by sellableRef).
   * Used at sell time to show purchase history hints on cart lines.
   */
  @GetMapping("/purchases/customer-product-history")
  public ResponseEntity<ApiResponse<CustomerProductHistoryResponse>> getCustomerProductHistory(
      @RequestParam(required = false) String customerId,
      @RequestParam(required = false) String customerPhone,
      @RequestParam String sellableRefs,
      @RequestParam(required = false, defaultValue = "3") Integer limit,
      @RequestParam(required = false) String excludePurchaseId,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    List<String> refs = java.util.Arrays.stream(sellableRefs.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();
    return ResponseEntity.ok(ApiResponse.success(
        customerProductHistoryService.getHistory(
            shopId, customerId, customerPhone, refs, limit, excludePurchaseId)));
  }
}

