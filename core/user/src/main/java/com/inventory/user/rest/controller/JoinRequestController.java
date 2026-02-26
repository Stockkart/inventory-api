package com.inventory.user.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.user.rest.dto.joinrequest.AcceptRejectJoinRequestRequest;
import com.inventory.user.rest.dto.joinrequest.JoinRequestListResponse;
import com.inventory.user.rest.dto.joinrequest.ProcessJoinRequestResponse;
import com.inventory.user.rest.dto.joinrequest.OwnerShopListResponse;
import com.inventory.user.rest.dto.joinrequest.SendJoinRequestRequest;
import com.inventory.user.rest.dto.joinrequest.SendJoinRequestResponse;
import com.inventory.user.service.JoinRequestService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class JoinRequestController {

  @Autowired
  private JoinRequestService joinRequestService;

  /**
   * Get shops owned by an email (for join-request flow: user enters owner email and selects a shop).
   */
  @GetMapping("/shops/by-owner-email")
  public ResponseEntity<ApiResponse<OwnerShopListResponse>> getShopsByOwnerEmail(
      @RequestParam String email,
      HttpServletRequest httpRequest) {
    String userId = (String) httpRequest.getAttribute("userId");
    if (!StringUtils.hasText(userId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
    return ResponseEntity.ok(ApiResponse.success(
        joinRequestService.getShopsByOwnerEmail(email)));
  }

  /**
   * Send a request to join a shop.
   *
   * @param request     The join request containing shopId and optional message
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
   * Get all join requests for a shop (owner only).
   * For multi-shop: pass shopId to get requests for that shop; omit to use active shop.
   *
   * @param shopId      Optional shop ID (required when owner has multiple shops)
   * @param httpRequest HTTP request to get userId from interceptor
   * @return List of join requests for the shop
   */
  @GetMapping("/shops/join-requests")
  public ResponseEntity<ApiResponse<JoinRequestListResponse>> getJoinRequests(
      @RequestParam(required = false) String shopId,
      HttpServletRequest httpRequest) {
    String userId = (String) httpRequest.getAttribute("userId");

    if (!StringUtils.hasText(userId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }

    return ResponseEntity.ok(ApiResponse.success(
        joinRequestService.getJoinRequestsForShop(userId, shopId)));
  }

  /**
   * Accept or reject a join request (owner only).
   *
   * @param requestId   The join request ID
   * @param request     The request containing action (ACCEPT or REJECT)
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

