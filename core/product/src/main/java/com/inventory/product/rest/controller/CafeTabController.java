package com.inventory.product.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.pluginengine.kot.CafeKotTab;
import com.inventory.pluginengine.kot.CafeKotTicket;
import com.inventory.product.rest.dto.request.CafeFlushRequest;
import com.inventory.product.rest.dto.request.CafeTabLineRequest;
import com.inventory.product.service.vertical.CafeKotTabService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cafe KOT tabs: composing a round before it is sent to the kitchen.
 *
 * <p>{@code shopId} and {@code userId} come from request attributes set by the authentication
 * interceptor, never from the body — every tab read and write is scoped to both, mirroring
 * {@code QuotationService}'s open-quotation rules. {@code flush} additionally requires a
 * non-blank {@code Idempotency-Key} header, rejected here before the service — and so the plugin
 * port — is ever touched: this is a call that reaches a kitchen.
 */
@RestController
@Latency(module = "product")
@RecordRequestRate(module = "product")
@RecordStatusCodes(module = "product")
@RequestMapping("/api/v1/cafe/tabs")
public class CafeTabController {

  private final CafeKotTabService cafeTabService;

  public CafeTabController(CafeKotTabService cafeTabService) {
    this.cafeTabService = cafeTabService;
  }

  @GetMapping
  public ResponseEntity<ApiResponse<List<CafeKotTab>>> list(HttpServletRequest httpRequest) {
    List<CafeKotTab> tabs = cafeTabService.list(shopId(httpRequest), userId(httpRequest));
    return ResponseEntity.ok(ApiResponse.success(tabs));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<CafeKotTab>> open(HttpServletRequest httpRequest) {
    CafeKotTab tab = cafeTabService.open(shopId(httpRequest), userId(httpRequest));
    return ResponseEntity.ok(ApiResponse.success(tab));
  }

  @PostMapping("/{tabId}/lines")
  public ResponseEntity<ApiResponse<CafeKotTab>> addOrUpdateLine(
      @PathVariable String tabId,
      @RequestBody CafeTabLineRequest request,
      HttpServletRequest httpRequest) {
    CafeKotTab tab =
        cafeTabService.addOrUpdateLine(
            shopId(httpRequest),
            userId(httpRequest),
            tabId,
            request.getLineRef(),
            request.getSellableRef(),
            request.getQuantity(),
            request.getNote());
    return ResponseEntity.ok(ApiResponse.success(tab));
  }

  @DeleteMapping("/{tabId}/lines/{lineRef}")
  public ResponseEntity<ApiResponse<CafeKotTab>> removeLine(
      @PathVariable String tabId, @PathVariable String lineRef, HttpServletRequest httpRequest) {
    CafeKotTab tab =
        cafeTabService.removeLine(shopId(httpRequest), userId(httpRequest), tabId, lineRef);
    return ResponseEntity.ok(ApiResponse.success(tab));
  }

  @DeleteMapping("/{tabId}")
  public ResponseEntity<ApiResponse<Void>> close(
      @PathVariable String tabId, HttpServletRequest httpRequest) {
    cafeTabService.close(shopId(httpRequest), userId(httpRequest), tabId);
    return ResponseEntity.ok(ApiResponse.success(null));
  }

  @PostMapping("/{tabId}/flush")
  public ResponseEntity<ApiResponse<List<CafeKotTicket>>> flush(
      @PathVariable String tabId,
      @RequestBody(required = false) CafeFlushRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      HttpServletRequest httpRequest) {
    CafeKotController.requireIdempotencyKey(idempotencyKey);
    String targetPurchaseId = request == null ? null : request.getPurchaseId();
    List<CafeKotTicket> tickets =
        cafeTabService.flush(
            shopId(httpRequest), userId(httpRequest), tabId, targetPurchaseId, idempotencyKey);
    return ResponseEntity.ok(ApiResponse.success(tickets));
  }

  private static String shopId(HttpServletRequest httpRequest) {
    return (String) httpRequest.getAttribute("shopId");
  }

  private static String userId(HttpServletRequest httpRequest) {
    return (String) httpRequest.getAttribute("userId");
  }
}
