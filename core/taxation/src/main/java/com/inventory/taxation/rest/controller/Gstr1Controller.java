package com.inventory.taxation.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.taxation.rest.dto.Gstr1ReportResponse;
import com.inventory.taxation.service.Gstr1ReportService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/taxation/gstr1")
@Slf4j
public class Gstr1Controller {

  @Autowired
  private Gstr1ReportService gstr1ReportService;

  /**
   * Get GSTR-1 report data for dashboard (period summary and all tab data).
   *
   * @param period period in YYYY-MM format (e.g. 2025-12)
   */
  @GetMapping
  public ResponseEntity<ApiResponse<Gstr1ReportResponse>> getReport(
      @RequestParam String period,
      HttpServletRequest httpRequest) {

    String shopId = (String) httpRequest.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Unauthorized access to taxation");
    }

    Gstr1ReportResponse response = Gstr1ReportResponse.fromContext(
        gstr1ReportService.getReportData(shopId, period));
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  /**
   * Download GSTR-1 as Excel (.xlsx) for the given period.
   *
   * @param period period in YYYY-MM format (e.g. 2025-12)
   */
  @GetMapping("/download")
  public ResponseEntity<byte[]> downloadExcel(
      @RequestParam String period,
      HttpServletRequest httpRequest) throws IOException {

    String shopId = (String) httpRequest.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Unauthorized access to taxation");
    }

    byte[] bytes = gstr1ReportService.generateExcel(shopId, period);
    YearMonth ym = YearMonth.parse(period);
    String monthAbbrev = Month.of(ym.getMonthValue()).getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    String filename = "GSTR1_RETURN_" + monthAbbrev + "_" + ym.getYear() + ".xlsx";

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    headers.setContentDispositionFormData("attachment", filename);
    headers.setContentLength(bytes.length);

    return ResponseEntity.ok().headers(headers).body(bytes);
  }

  /**
   * Download GSTR-1 as offline-return JSON matching the GST utility layout (gstin/fp/version/hash/
   * b2b/b2cs/hsn/doc_issue).
   */
  @GetMapping("/download/offline-return")
  public ResponseEntity<byte[]> downloadOfflinePortalJson(
      @RequestParam String period,
      HttpServletRequest httpRequest) {

    String shopId = (String) httpRequest.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Unauthorized access to taxation");
    }

    YearMonth ym = YearMonth.parse(period);
    String fp = String.format(Locale.ROOT, "%02d%d", ym.getMonthValue(), ym.getYear());
    byte[] bytes = gstr1ReportService.generateOfflinePortalJson(shopId, period);
    String filename = "GSTR1_" + fp + ".json";

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setContentDispositionFormData("attachment", filename);
    headers.setContentLength(bytes.length);

    return ResponseEntity.ok().headers(headers).body(bytes);
  }
}
