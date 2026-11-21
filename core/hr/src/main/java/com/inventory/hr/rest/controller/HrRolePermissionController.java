package com.inventory.hr.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.hr.rest.dto.request.UpdateHrRolePermissionsRequest;
import com.inventory.hr.rest.dto.response.HrRolePermissionsResponse;
import com.inventory.hr.service.RolePermissionService;
import com.inventory.hr.utils.HrRequestUtils;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HR role grid; owner only (checked in {@link RolePermissionService}). */
@RestController
@RequestMapping("/api/v1/hr/role-permissions")
@Latency(module = "hr")
@RecordRequestRate(module = "hr")
@RecordStatusCodes(module = "hr")
public class HrRolePermissionController {

  @Autowired private RolePermissionService rolePermissionService;

  @GetMapping
  public ResponseEntity<ApiResponse<HrRolePermissionsResponse>> get(HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(rolePermissionService.get(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request))));
  }

  @PutMapping
  public ResponseEntity<ApiResponse<HrRolePermissionsResponse>> update(
      @RequestBody UpdateHrRolePermissionsRequest body, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(rolePermissionService.update(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), body)));
  }
}
