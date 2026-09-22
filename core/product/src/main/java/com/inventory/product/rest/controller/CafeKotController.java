package com.inventory.product.rest.controller;

import com.inventory.common.exception.ValidationException;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.product.service.vertical.CafeKotService;
import jakarta.servlet.http.HttpServletRequest;
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
 * Cafe kitchen ticket documents, and reprint.
 *
 * <p>shopId comes from request attributes set by the authentication interceptor, never from the
 * body. This controller renders a ticket's document — it does not print; the frontend fetches the
 * document and prints it. Reprint stamps the slip and bumps a count; it does not print either.
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
}
