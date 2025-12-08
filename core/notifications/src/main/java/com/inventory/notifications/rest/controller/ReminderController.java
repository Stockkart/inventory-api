package com.inventory.notifications.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.constants.ErrorCode;
import com.inventory.notifications.rest.dto.*;
import com.inventory.notifications.service.ReminderService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reminders")
public class ReminderController {

  @Autowired
  private ReminderService reminderService;

  // LIST by shop
  @GetMapping
  public ResponseEntity<ApiResponse<ReminderListResponse>> list(HttpServletRequest httpRequest) {
    // Get shopId from request attributes (set by AuthenticationInterceptor)
    String shopId = (String) httpRequest.getAttribute("shopId");
    
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "Unauthorized access to shop reminders");
    }
    
    return ResponseEntity.ok(ApiResponse.success(reminderService.list(shopId)));
  }

  // GET single reminder
  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<ReminderResponse>> get(@PathVariable String id) {
    return ResponseEntity.ok(ApiResponse.success(reminderService.get(id)));
  }

  // CREATE manual reminder
  @PostMapping
  public ResponseEntity<ApiResponse<ReminderResponse>> create(
      @RequestBody CreateReminderRequest request,
      HttpServletRequest httpRequest) {
    // Get shopId from request attributes (set by AuthenticationInterceptor)
    String shopId = (String) httpRequest.getAttribute("shopId");
    
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "Unauthorized access to shop reminders");
    }
    
    // Set shopId from interceptor to ensure user can only create reminders for their shop
    request.setShopId(shopId);
    
    return ResponseEntity.ok(ApiResponse.success(reminderService.create(request)));
  }

  // UPDATE manual reminder
  @PutMapping("/{id}")
  public ResponseEntity<ApiResponse<ReminderResponse>> update(
          @PathVariable String id,
          @RequestBody UpdateReminderRequest request
  ) {
    return ResponseEntity.ok(ApiResponse.success(reminderService.update(id, request)));
  }

  // DELETE reminder
  @DeleteMapping("/{id}")
  public ResponseEntity<ApiResponse<Long>> delete(@PathVariable String id) {
    return ResponseEntity.ok(ApiResponse.success(reminderService.delete(id)));
  }

  // SNOOZE (already present)
  @PostMapping("/{id}/snooze")
  public ResponseEntity<ApiResponse<ReminderResponse>> snooze(
          @PathVariable String id,
          @RequestBody SnoozeReminderRequest request
  ) {
    return ResponseEntity.ok(ApiResponse.success(reminderService.snooze(id, request)));
  }
}
