package com.inventory.user.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.constants.ErrorCode;
import com.inventory.user.rest.dto.invitation.*;
import com.inventory.user.service.InvitationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class InvitationController {

  @Autowired
  private InvitationService invitationService;

  /**
   * Send an invitation to a user to join a shop.
   * 
   * @param shopId The shop ID from path
   * @param request The invitation request containing invitee email and role
   * @param httpRequest HTTP request to get userId and shopId from interceptor
   * @return The created invitation response
   */
  @PostMapping("/shops/{shopId}/invitations")
  public ResponseEntity<ApiResponse<SendInvitationResponse>> sendInvitation(
          @PathVariable String shopId,
          @RequestBody SendInvitationRequest request,
          HttpServletRequest httpRequest) {
    // Get userId and shopId from request attributes (set by AuthenticationInterceptor)
    String inviterUserId = (String) httpRequest.getAttribute("userId");
    String shopIdFromInterceptor = (String) httpRequest.getAttribute("shopId");
    
    if (!StringUtils.hasText(inviterUserId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
    
    // Validate that the shopId in path matches the user's shopId from interceptor
    if (!StringUtils.hasText(shopIdFromInterceptor) || !shopId.equals(shopIdFromInterceptor)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User does not belong to this shop");
    }
    
    return ResponseEntity.ok(ApiResponse.success(
            invitationService.sendInvitation(shopId, inviterUserId, request)));
  }

  /**
   * Accept an invitation.
   * 
   * @param invitationId The invitation ID
   * @param httpRequest HTTP request to get userId from interceptor
   * @return The acceptance response
   */
  @PostMapping("/invitations/{invitationId}/accept")
  public ResponseEntity<ApiResponse<AcceptInvitationResponse>> acceptInvitation(
          @PathVariable String invitationId,
          HttpServletRequest httpRequest) {
    // Get userId from request attributes (set by AuthenticationInterceptor)
    String userId = (String) httpRequest.getAttribute("userId");
    
    if (!StringUtils.hasText(userId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
    
    return ResponseEntity.ok(ApiResponse.success(
            invitationService.acceptInvitation(invitationId, userId)));
  }

  /**
   * Get all invitations for the current user.
   *
   * @param httpRequest HTTP request to get userId from interceptor
   * @return List of invitations for the user
   */
  @GetMapping("/users/invitations")
  public ResponseEntity<ApiResponse<InvitationListResponse>> getInvitationsForUser(
          HttpServletRequest httpRequest) {
    // Get userId from request attributes (set by AuthenticationInterceptor)
    String userId = (String) httpRequest.getAttribute("userId");

    if (!StringUtils.hasText(userId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }

    return ResponseEntity.ok(ApiResponse.success(
            invitationService.getInvitationsForUser(userId)));
  }

  /**
   * Get all invitations for a specific shop.
   * 
   * @param shopId The shop ID from path
   * @param httpRequest HTTP request to get shopId from interceptor for validation
   * @return List of invitations for the shop
   */
  @GetMapping("/shops/{shopId}/invitations")
  public ResponseEntity<ApiResponse<InvitationListResponse>> getInvitationsForShop(
          @PathVariable String shopId,
          HttpServletRequest httpRequest) {
    // Get shopId from interceptor for validation
    String shopIdFromInterceptor = (String) httpRequest.getAttribute("shopId");
    String userId = (String) httpRequest.getAttribute("userId");

    // Validate that the shopId in path matches the user's shopId from interceptor
    if (!StringUtils.hasText(shopIdFromInterceptor) || !shopId.equals(shopIdFromInterceptor)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User does not belong to this shop");
    }
    
    return ResponseEntity.ok(ApiResponse.success(
            invitationService.getInvitationsForShop(shopId, userId)));
  }

  /**
   * Get all users for a shop (owner and invited users).
   * 
   * @param shopId The shop ID from path
   * @param httpRequest HTTP request to get shopId from interceptor for validation
   * @return List of users associated with the shop
   */
  @GetMapping("/shops/{shopId}/users/all")
  public ResponseEntity<ApiResponse<ShopUserListResponse>> getUsersForShop(
          @PathVariable String shopId,
          HttpServletRequest httpRequest) {
    // Get shopId from interceptor for validation
    String shopIdFromInterceptor = (String) httpRequest.getAttribute("shopId");
    
    // Validate that the shopId in path matches the user's shopId from interceptor
    if (!StringUtils.hasText(shopIdFromInterceptor) || !shopId.equals(shopIdFromInterceptor)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User does not belong to this shop");
    }
    
    return ResponseEntity.ok(ApiResponse.success(
            invitationService.getUsersForShop(shopId)));
  }
}

