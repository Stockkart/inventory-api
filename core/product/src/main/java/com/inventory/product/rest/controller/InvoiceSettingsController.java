package com.inventory.product.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.product.rest.dto.request.PreviewInvoiceSettingsRequest;
import com.inventory.product.rest.dto.request.UpdateInvoiceSettingsRequest;
import com.inventory.product.rest.dto.response.InvoiceSettingsResponse;
import com.inventory.product.service.InvoiceSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/shops/active-shop/invoice-settings")
@Latency(module = "product")
@RecordRequestRate(module = "product")
@RecordStatusCodes(module = "product")
@Slf4j
public class InvoiceSettingsController {

  @Autowired
  private InvoiceSettingsService invoiceSettingsService;

  @GetMapping
  public ResponseEntity<ApiResponse<InvoiceSettingsResponse>> getSettings(
      HttpServletRequest httpRequest) {
    String userId = requireUserId(httpRequest);
    String shopId = requireShopId(httpRequest);
    return ResponseEntity.ok(
        ApiResponse.success(invoiceSettingsService.getSettings(shopId, userId)));
  }

  @PutMapping
  public ResponseEntity<ApiResponse<InvoiceSettingsResponse>> updateSettings(
      @RequestBody UpdateInvoiceSettingsRequest request, HttpServletRequest httpRequest) {
    String userId = requireUserId(httpRequest);
    String shopId = requireShopId(httpRequest);
    return ResponseEntity.ok(
        ApiResponse.success(invoiceSettingsService.updateSettings(shopId, userId, request)));
  }

  @PostMapping(value = "/preview", produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<String> preview(
      @RequestBody(required = false) PreviewInvoiceSettingsRequest request,
      HttpServletRequest httpRequest) {
    String userId = requireUserId(httpRequest);
    String shopId = requireShopId(httpRequest);
    log.info("Generating invoice settings HTML preview for shop: {}", shopId);

    String html = invoiceSettingsService.previewHtml(shopId, userId, request);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8));
    headers.setCacheControl("no-store");

    return ResponseEntity.ok().headers(headers).body(html);
  }

  private static String requireUserId(HttpServletRequest request) {
    String userId = (String) request.getAttribute("userId");
    if (!StringUtils.hasText(userId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
    return userId;
  }

  private static String requireShopId(HttpServletRequest request) {
    String shopId = (String) request.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop context is required");
    }
    return shopId;
  }
}
