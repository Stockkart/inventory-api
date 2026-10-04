package com.inventory.taxation.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.taxation.rest.dto.HsnGstRatesResponse;
import com.inventory.taxation.service.HsnGstRateService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The GST rates the rate notifications allow for an HSN, for a stock-in row to offer. */
@RestController
@RequestMapping("/api/v1/taxation/hsn-gst-rates")
@Latency(module = "taxation")
@RecordRequestRate(module = "taxation")
@RecordStatusCodes(module = "taxation")
public class HsnGstRateController {

  @Autowired
  private HsnGstRateService hsnGstRateService;

  /** Empty {@code rates} when the HSN is not in the table. */
  @GetMapping
  public ResponseEntity<ApiResponse<HsnGstRatesResponse>> ratesFor(
      @RequestParam("hsn") String hsn,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Unauthorized access to taxation");
    }
    return ResponseEntity.ok(ApiResponse.success(hsnGstRateService.ratesFor(hsn)));
  }
}
