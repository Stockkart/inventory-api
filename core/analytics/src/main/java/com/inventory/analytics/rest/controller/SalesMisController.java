package com.inventory.analytics.rest.controller;

import com.inventory.analytics.domain.model.MoneyFilter;
import com.inventory.analytics.domain.model.SalesMisExportScope;
import com.inventory.analytics.domain.model.SalesMisTxnType;
import com.inventory.analytics.rest.dto.response.SalesMisResponse;
import com.inventory.analytics.service.SalesMisService;
import com.inventory.analytics.service.SalesMisService.ExportFile;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequestMapping("/api/v1/reports/sales-mis")
@RequiredArgsConstructor
@Slf4j
public class SalesMisController {

  private final SalesMisService salesMisService;

  @GetMapping
  public ResponseEntity<ApiResponse<SalesMisResponse>> getReport(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String customerId,
      @RequestParam(required = false) Set<SalesMisTxnType> txnTypes,
      @RequestParam(required = false, defaultValue = "ALL") MoneyFilter moneyFilter,
      @RequestParam(required = false) String q,
      HttpServletRequest httpRequest) {
    String shopId = requireShopId(httpRequest);
    return ResponseEntity.ok(
        ApiResponse.success(
            salesMisService.getSalesMis(shopId, from, to, customerId, txnTypes, moneyFilter, q)));
  }

  @GetMapping("/excel")
  public ResponseEntity<byte[]> downloadExcel(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String customerId,
      @RequestParam(required = false) Set<SalesMisTxnType> txnTypes,
      @RequestParam(required = false, defaultValue = "ALL") MoneyFilter moneyFilter,
      @RequestParam(required = false) String q,
      @RequestParam(required = false, defaultValue = "DAILY") SalesMisExportScope scope,
      HttpServletRequest httpRequest) {
    String shopId = requireShopId(httpRequest);
    ExportFile file =
        salesMisService.exportExcel(shopId, from, to, customerId, txnTypes, moneyFilter, q, scope);
    return attachment(
        file,
        MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
  }

  @GetMapping("/pdf")
  public ResponseEntity<byte[]> downloadPdf(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String customerId,
      @RequestParam(required = false) Set<SalesMisTxnType> txnTypes,
      @RequestParam(required = false, defaultValue = "ALL") MoneyFilter moneyFilter,
      @RequestParam(required = false) String q,
      @RequestParam(required = false, defaultValue = "DAILY") SalesMisExportScope scope,
      HttpServletRequest httpRequest) {
    String shopId = requireShopId(httpRequest);
    ExportFile file =
        salesMisService.exportPdf(shopId, from, to, customerId, txnTypes, moneyFilter, q, scope);
    return attachment(file, MediaType.APPLICATION_PDF);
  }

  private static ResponseEntity<byte[]> attachment(ExportFile file, MediaType contentType) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(contentType);
    headers.setContentDispositionFormData("attachment", file.filename());
    headers.setContentLength(file.content().length);
    return ResponseEntity.ok().headers(headers).body(file.content());
  }

  private static String requireShopId(HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Unauthorized access to reports");
    }
    return shopId;
  }
}
