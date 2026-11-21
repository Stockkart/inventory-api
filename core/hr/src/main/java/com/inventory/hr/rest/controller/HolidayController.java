package com.inventory.hr.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.hr.rest.dto.request.SaveHolidayRequest;
import com.inventory.hr.rest.dto.response.HolidayResponse;
import com.inventory.hr.service.HolidayService;
import com.inventory.hr.utils.HrRequestUtils;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hr/holidays")
@Latency(module = "hr")
@RecordRequestRate(module = "hr")
@RecordStatusCodes(module = "hr")
public class HolidayController {

  @Autowired private HolidayService holidayService;

  @GetMapping
  public ResponseEntity<ApiResponse<List<HolidayResponse>>> list(
      @RequestParam(required = false) Integer year, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(holidayService.list(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), year)));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<HolidayResponse>> create(
      @RequestBody SaveHolidayRequest body, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(holidayService.create(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), body)));
  }

  @PutMapping("/{id}")
  public ResponseEntity<ApiResponse<HolidayResponse>> update(
      @PathVariable String id, @RequestBody SaveHolidayRequest body, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(holidayService.update(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), id, body)));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<ApiResponse<Void>> delete(
      @PathVariable String id, HttpServletRequest request) {
    holidayService.delete(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), id);
    return ResponseEntity.ok(ApiResponse.success(null));
  }
}
