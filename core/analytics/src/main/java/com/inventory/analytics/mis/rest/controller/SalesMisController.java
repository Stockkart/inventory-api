package com.inventory.analytics.mis.rest.controller;


import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.analytics.mis.rest.dto.MisSalesReportResponse;
import com.inventory.analytics.mis.service.SalesMisService;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/mis/sales")
@RequiredArgsConstructor
@Latency(module = "analytics")
@RecordRequestRate(module = "analytics")
@RecordStatusCodes(module = "analytics")
public class SalesMisController {

  private final SalesMisService salesMisService;

  @GetMapping
  public ResponseEntity<ApiResponse<MisSalesReportResponse>> getReport(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String paymentMethod,
      @RequestParam(required = false) String customerId,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      HttpServletRequest request) {
    String shopId = requireShopId(request);
    return ResponseEntity.ok(
        ApiResponse.success(
            salesMisService.getReport(
                shopId, from, to, paymentMethod, customerId, q, page, size)));
  }

  @GetMapping("/excel")
  public ResponseEntity<byte[]> excel(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String paymentMethod,
      @RequestParam(required = false) String customerId,
      @RequestParam(required = false) String q,
      HttpServletRequest request) {
    String shopId = requireShopId(request);
    byte[] body =
        salesMisService.exportExcel(shopId, null, from, to, paymentMethod, customerId, q);
    return attachment(body, "sales-mis.xlsx", MisMedia.XLSX);
  }

  @GetMapping("/pdf")
  public ResponseEntity<byte[]> pdf(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String paymentMethod,
      @RequestParam(required = false) String customerId,
      @RequestParam(required = false) String q,
      HttpServletRequest request) {
    String shopId = requireShopId(request);
    byte[] body =
        salesMisService.exportPdf(shopId, null, from, to, paymentMethod, customerId, q);
    return attachment(body, "sales-mis.pdf", MediaType.APPLICATION_PDF);
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
