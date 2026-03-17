package com.inventory.taxation.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.taxation.rest.dto.Gstr3bReportResponse;
import com.inventory.taxation.service.Gstr3bReportService;
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
@RequestMapping("/api/v1/taxation/gstr3b")
@Slf4j
public class Gstr3bController {

  @Autowired
  private Gstr3bReportService gstr3bReportService;

  /**
   * Get GSTR-3B report data for dashboard.
   *
   * @param period period in YYYY-MM format (e.g. 2025-12)
   */
  @GetMapping
  public ResponseEntity<ApiResponse<Gstr3bReportResponse>> getReport(
      @RequestParam String period,
      HttpServletRequest httpRequest) {

    String shopId = (String) httpRequest.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Unauthorized access to taxation");
    }

    Gstr3bReportResponse response = Gstr3bReportResponse.fromContext(
        gstr3bReportService.getReportData(shopId, period));
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  /**
   * Download GSTR-3B as Excel (.xlsx) for the given period.
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

    byte[] bytes = gstr3bReportService.generateExcel(shopId, period);
    YearMonth ym = YearMonth.parse(period);
    String monthAbbrev = Month.of(ym.getMonthValue()).getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    String filename = "GSTR3B_RETURN_" + monthAbbrev + "_" + ym.getYear() + ".xlsx";

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    headers.setContentDispositionFormData("attachment", filename);
    headers.setContentLength(bytes.length);

    return ResponseEntity.ok().headers(headers).body(bytes);
  }
}
