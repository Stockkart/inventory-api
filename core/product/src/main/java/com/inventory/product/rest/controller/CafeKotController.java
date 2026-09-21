package com.inventory.product.rest.controller;

import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.product.service.vertical.CafeKotService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cafe kitchen ticket documents.
 *
 * <p>shopId comes from request attributes set by the authentication interceptor, never from the
 * body. This controller renders a ticket's document — it does not print; the frontend fetches the
 * document and prints it.
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

  private static String shopId(HttpServletRequest httpRequest) {
    return (String) httpRequest.getAttribute("shopId");
  }
}
