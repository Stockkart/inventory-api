package com.inventory.analytics.rest.controller;

import com.inventory.analytics.rest.dto.response.PartyMoneyMisResponse;
import com.inventory.analytics.service.PartyMoneyMisExcelWriter;
import com.inventory.analytics.service.PartyMoneyMisPdfService;
import com.inventory.analytics.service.PartyMoneyMisService;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
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
@RequestMapping("/api/v1/reports/party-money-mis")
@RequiredArgsConstructor
@Slf4j
public class PartyMoneyMisController {

  private final PartyMoneyMisService partyMoneyMisService;
  private final PartyMoneyMisExcelWriter excelWriter;
  private final PartyMoneyMisPdfService pdfService;

  @GetMapping
  public ResponseEntity<ApiResponse<PartyMoneyMisResponse>> getReport(
      @RequestParam(defaultValue = "VENDOR") String side,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String partyId,
      @RequestParam(required = false) String txnTypes,
      @RequestParam(required = false, defaultValue = "ALL") String moneyFilter,
      @RequestParam(required = false) String q,
      HttpServletRequest httpRequest) {
    String shopId = requireShopId(httpRequest);
    if (!"VENDOR".equalsIgnoreCase(side)) {
      throw new IllegalArgumentException("Only side=VENDOR is supported in this release");
    }
    PartyMoneyMisResponse response =
        partyMoneyMisService.getVendorMis(
            shopId, from, to, partyId, parseTxnTypes(txnTypes), moneyFilter, q);
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  @GetMapping("/excel")
  public ResponseEntity<byte[]> downloadExcel(
      @RequestParam(defaultValue = "VENDOR") String side,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String partyId,
      @RequestParam(required = false) String txnTypes,
      @RequestParam(required = false, defaultValue = "ALL") String moneyFilter,
      @RequestParam(required = false) String q,
      HttpServletRequest httpRequest)
      throws Exception {
    String shopId = requireShopId(httpRequest);
    PartyMoneyMisResponse report =
        partyMoneyMisService.getVendorMis(
            shopId, from, to, partyId, parseTxnTypes(txnTypes), moneyFilter, q);
    byte[] bytes = excelWriter.write(report);
    String filename =
        "vendor-money-mis-"
            + (report.getFrom() != null ? report.getFrom() : "from")
            + "-"
            + (report.getTo() != null ? report.getTo() : "to")
            + ".xlsx";
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(
        MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    headers.setContentDispositionFormData("attachment", filename);
    headers.setContentLength(bytes.length);
    return ResponseEntity.ok().headers(headers).body(bytes);
  }

  @GetMapping("/pdf")
  public ResponseEntity<byte[]> downloadPdf(
      @RequestParam(defaultValue = "VENDOR") String side,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String partyId,
      @RequestParam(required = false) String txnTypes,
      @RequestParam(required = false, defaultValue = "ALL") String moneyFilter,
      @RequestParam(required = false) String q,
      HttpServletRequest httpRequest) {
    String shopId = requireShopId(httpRequest);
    PartyMoneyMisResponse report =
        partyMoneyMisService.getVendorMis(
            shopId, from, to, partyId, parseTxnTypes(txnTypes), moneyFilter, q);
    byte[] bytes = pdfService.generate(report, "Shop");
    String filename =
        "vendor-money-mis-"
            + (report.getFrom() != null ? report.getFrom() : "from")
            + "-"
            + (report.getTo() != null ? report.getTo() : "to")
            + ".pdf";
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_PDF);
    headers.setContentDispositionFormData("attachment", filename);
    headers.setContentLength(bytes.length);
    return ResponseEntity.ok().headers(headers).body(bytes);
  }

  private static String requireShopId(HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Unauthorized access to reports");
    }
    return shopId;
  }

  private static Set<String> parseTxnTypes(String txnTypes) {
    if (!StringUtils.hasText(txnTypes)) {
      return Set.of();
    }
    return Arrays.stream(txnTypes.split(","))
        .map(String::trim)
        .filter(StringUtils::hasText)
        .map(String::toUpperCase)
        .collect(Collectors.toSet());
  }
}
