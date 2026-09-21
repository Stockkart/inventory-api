package com.inventory.product.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.pluginengine.kot.CafeKotTicket;
import com.inventory.product.service.vertical.CafeKotService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cafe cart punches and kitchen ticket documents.
 *
 * <p>shopId and userId come from request attributes set by the authentication interceptor, never
 * from the body. This controller creates and returns tickets — it does not print; the frontend
 * fetches the document and prints it.
 */
@RestController
@Latency(module = "product")
@RecordRequestRate(module = "product")
@RecordStatusCodes(module = "product")
@RequestMapping("/api/v1/cafe")
public class CafeKotController {

  private final CafeKotService cafeKotService;

  public CafeKotController(CafeKotService cafeKotService) {
    this.cafeKotService = cafeKotService;
  }

  /** Punches the cart. The Idempotency-Key header is required — this is the call that reaches a kitchen. */
  @PostMapping("/purchases/{purchaseId}/kots")
  public ResponseEntity<ApiResponse<List<CafeKotTicket>>> punch(
      @PathVariable String purchaseId,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      HttpServletRequest httpRequest) {
    return ResponseEntity.ok(
        ApiResponse.success(
            cafeKotService.punch(
                shopId(httpRequest), userId(httpRequest), purchaseId, idempotencyKey)));
  }

  @GetMapping("/kots/{kotId}/document")
  public ResponseEntity<byte[]> kotDocument(
      @PathVariable String kotId, HttpServletRequest httpRequest) {
    byte[] bytes = cafeKotService.kotDocument(shopId(httpRequest), kotId);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_PDF);
    headers.setContentDispositionFormData("attachment", "kot_" + kotId + ".pdf");
    headers.setContentLength(bytes.length);
    return ResponseEntity.ok().headers(headers).body(bytes);
  }

  private static String shopId(HttpServletRequest httpRequest) {
    return (String) httpRequest.getAttribute("shopId");
  }

  private static String userId(HttpServletRequest httpRequest) {
    return (String) httpRequest.getAttribute("userId");
  }
}
