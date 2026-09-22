package com.inventory.product.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.ValidationException;
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
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cafe cart punches, kitchen ticket documents, and reprint.
 *
 * <p>shopId and userId come from request attributes set by the authentication interceptor, never
 * from the body. This controller creates and returns tickets — it does not print; the frontend
 * fetches the document and prints it. Reprint stamps the slip and bumps a count; it does not
 * print either.
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

  /**
   * Punches the cart: the Sell screen's Print KOT. Sends the difference between the cart and what
   * the kitchen already has, so a fresh cart sends everything and a later press sends only what
   * was added since.
   *
   * <p>The {@code Idempotency-Key} header is required and rejected here, before the service is
   * touched — this is the call that reaches a kitchen.
   */
  @PostMapping("/purchases/{purchaseId}/kots")
  public ResponseEntity<ApiResponse<List<CafeKotTicket>>> punch(
      @PathVariable String purchaseId,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      HttpServletRequest httpRequest) {
    requireIdempotencyKey(idempotencyKey);
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

  /**
   * Reprints an already-issued ticket's document: bumps its reprint count and renders it stamped
   * so a cook reading it cannot mistake it for a second order. Creates no new ticket. Requires a
   * non-blank {@code Idempotency-Key} — this reaches a kitchen the same way a flush does —
   * rejected here, before the service (and the port) is touched at all.
   *
   * <p>An ordinary document fetch ({@link #kotDocument}) of the same ticket renders unstamped (or
   * {@code CANCELLED}); only a reprint request renders {@code REPRINT}.
   */
  @PostMapping("/kots/{kotId}/reprint")
  public ResponseEntity<byte[]> reprint(
      @PathVariable String kotId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      HttpServletRequest httpRequest) {
    requireIdempotencyKey(idempotencyKey);
    byte[] bytes = cafeKotService.reprint(shopId(httpRequest), kotId, idempotencyKey);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_PDF);
    headers.setContentDispositionFormData("attachment", "kot_" + kotId + "_reprint.pdf");
    headers.setContentLength(bytes.length);
    return ResponseEntity.ok().headers(headers).body(bytes);
  }

  static void requireIdempotencyKey(String idempotencyKey) {
    if (!StringUtils.hasText(idempotencyKey)) {
      throw new ValidationException("Idempotency-Key header is required");
    }
  }

  private static String shopId(HttpServletRequest httpRequest) {
    return (String) httpRequest.getAttribute("shopId");
  }

  private static String userId(HttpServletRequest httpRequest) {
    return (String) httpRequest.getAttribute("userId");
  }
}
