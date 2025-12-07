package com.inventory.user.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.constants.ErrorCode;
import com.inventory.user.rest.dto.joinrequest.AcceptRejectJoinRequestRequest;
import com.inventory.user.rest.dto.joinrequest.JoinRequestListResponse;
import com.inventory.user.rest.dto.joinrequest.ProcessJoinRequestResponse;
import com.inventory.user.rest.dto.joinrequest.SendJoinRequestRequest;
import com.inventory.user.rest.dto.joinrequest.SendJoinRequestResponse;
import com.inventory.user.service.JoinRequestService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class JoinRequestController {

  @Autowired
  private JoinRequestService joinRequestService;

  /**
   * Send a request to join a shop.
   * 
   * @param request The join request containing shopId and optional message
   * @param httpRequest HTTP request to get userId from interceptor
   * @return The created join request response
   */
  @PostMapping("/shops/join-request")
  public ResponseEntity<ApiResponse<SendJoinRequestResponse>> sendJoinRequest(
          @RequestBody SendJoinRequestRequest request,
          HttpServletRequest httpRequest) {
    // Get userId from request attributes (set by AuthenticationInterceptor)
    String userId = (String) httpRequest.getAttribute("userId");
    
    if (!StringUtils.hasText(userId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
    
    return ResponseEntity.ok(ApiResponse.success(
            joinRequestService.sendJoinRequest(userId, request)));
  }

  /**
   * Get all join requests for the current user's shop (owner only).
   * 
   * @param httpRequest HTTP request to get userId from interceptor
   * @return List of join requests for the shop
   */
  @GetMapping("/shops/join-requests")
  public ResponseEntity<ApiResponse<JoinRequestListResponse>> getJoinRequests(
          HttpServletRequest httpRequest) {
    // Get userId from request attributes (set by AuthenticationInterceptor)
    String userId = (String) httpRequest.getAttribute("userId");
    
    if (!StringUtils.hasText(userId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
    
    return ResponseEntity.ok(ApiResponse.success(
            joinRequestService.getJoinRequestsForShop(userId)));
  }

  /**
   * Accept or reject a join request (owner only).
   * 
   * @param requestId The join request ID
   * @param request The request containing action (ACCEPT or REJECT)
   * @param httpRequest HTTP request to get userId from interceptor
   * @return The processing response
   */
  @PostMapping("/shops/join-requests/{requestId}/process")
  public ResponseEntity<ApiResponse<ProcessJoinRequestResponse>> processJoinRequest(
          @PathVariable String requestId,
          @RequestBody AcceptRejectJoinRequestRequest request,
          HttpServletRequest httpRequest) {
    // Get userId from request attributes (set by AuthenticationInterceptor)
    String userId = (String) httpRequest.getAttribute("userId");
    
    if (!StringUtils.hasText(userId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
    
    return ResponseEntity.ok(ApiResponse.success(
            joinRequestService.processJoinRequest(requestId, userId, request)));
  }
}

