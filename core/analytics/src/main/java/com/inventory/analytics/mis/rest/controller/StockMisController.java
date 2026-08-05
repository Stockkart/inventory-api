package com.inventory.analytics.mis.rest.controller;

import com.inventory.analytics.mis.rest.dto.MisStockReportResponse;
import com.inventory.analytics.mis.service.StockMisService;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/mis/stock")
@RequiredArgsConstructor
public class StockMisController {

  private final StockMisService stockMisService;

  @GetMapping
  public ResponseEntity<ApiResponse<MisStockReportResponse>> getReport(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Boolean lowStockOnly,
      @RequestParam(required = false) Boolean deadStockOnly,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      HttpServletRequest request) {
    String shopId = requireShopId(request);
    return ResponseEntity.ok(
        ApiResponse.success(
            stockMisService.getReport(shopId, q, lowStockOnly, deadStockOnly, page, size)));
  }

  @GetMapping("/excel")
  public ResponseEntity<byte[]> excel(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Boolean lowStockOnly,
      @RequestParam(required = false) Boolean deadStockOnly,
      HttpServletRequest request) {
    String shopId = requireShopId(request);
    byte[] body = stockMisService.exportExcel(shopId, null, q, lowStockOnly, deadStockOnly);
    return attachment(body, "stock-mis.xlsx", MisMedia.XLSX);
  }

  @GetMapping("/pdf")
  public ResponseEntity<byte[]> pdf(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Boolean lowStockOnly,
      @RequestParam(required = false) Boolean deadStockOnly,
      HttpServletRequest request) {
    String shopId = requireShopId(request);
    byte[] body = stockMisService.exportPdf(shopId, null, q, lowStockOnly, deadStockOnly);
    return attachment(body, "stock-mis.pdf", MediaType.APPLICATION_PDF);
  }

  private static String requireShopId(HttpServletRequest request) {
    String shopId = (String) request.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Unauthorized access to MIS");
    }
    return shopId;
  }

  private static ResponseEntity<byte[]> attachment(byte[] body, String filename, MediaType type) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
        .contentType(type)
        .body(body);
  }

  private static final class MisMedia {
    private static final MediaType XLSX =
        MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
  }
}
