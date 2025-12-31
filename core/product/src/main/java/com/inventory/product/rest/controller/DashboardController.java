package com.inventory.product.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.product.rest.dto.dashboard.DashboardResponse;
import com.inventory.product.service.DashboardService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

  @Autowired
  private DashboardService dashboardService;

  @GetMapping
  public ResponseEntity<ApiResponse<DashboardResponse>> getDashboard(HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    DashboardResponse response = dashboardService.getDashboard(shopId);
    return ResponseEntity.ok(ApiResponse.success(response));
  }
}

