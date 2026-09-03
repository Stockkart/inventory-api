package com.inventory.analytics.mis.rest.controller;

import com.inventory.analytics.mis.rest.dto.MisBankSummaryReportResponse;
import com.inventory.analytics.mis.service.BankSummaryMisService;
import com.inventory.analytics.mis.service.StockPeriodCloseService;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.product.domain.model.StockPeriodSnapshot;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/mis/bank-summary")
@RequiredArgsConstructor
@Latency(module = "analytics")
@RecordRequestRate(module = "analytics")
@RecordStatusCodes(module = "analytics")
public class BankSummaryMisController {

  private final BankSummaryMisService bankSummaryMisService;
  private final StockPeriodCloseService stockPeriodCloseService;

  @GetMapping
  public ResponseEntity<ApiResponse<MisBankSummaryReportResponse>> getReport(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      HttpServletRequest request) {
    String shopId = requireShopId(request);
    return ResponseEntity.ok(
        ApiResponse.success(bankSummaryMisService.getReport(shopId, from, to, q, page, size)));
  }

  @GetMapping("/excel")
  public ResponseEntity<byte[]> excel(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String q,
      HttpServletRequest request) {
    String shopId = requireShopId(request);
    byte[] body = bankSummaryMisService.exportExcel(shopId, null, from, to, q);
    return attachment(body, "bank-summary.xlsx", MisMedia.XLSX);
  }

  @GetMapping("/pdf")
  public ResponseEntity<byte[]> pdf(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String q,
      HttpServletRequest request) {
    String shopId = requireShopId(request);
    byte[] body = bankSummaryMisService.exportPdf(shopId, null, from, to, q);
    return attachment(body, "bank-summary.pdf", MediaType.APPLICATION_PDF);
  }

  /** Freezes the closing stock value for a period, which the next period then opens from. */
  @PostMapping("/close")
  public ResponseEntity<ApiResponse<StockPeriodSnapshot>> close(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
      @RequestParam(required = false, defaultValue = "false") boolean force,
      HttpServletRequest request) {
    String shopId = requireShopId(request);
    String userId = (String) request.getAttribute("userId");
    StockPeriodSnapshot snapshot =
        stockPeriodCloseService.close(shopId, periodEnd, force, userId);
    return ResponseEntity.ok(
        ApiResponse.success(snapshot, "Closed stock period ending " + periodEnd + "."));
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
