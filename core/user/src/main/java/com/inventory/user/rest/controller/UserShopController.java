package com.inventory.user.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.ValidationException;
import com.inventory.user.rest.dto.invitation.UserShopListResponse;
import com.inventory.user.rest.dto.membership.SwitchActiveShopRequest;
import com.inventory.user.rest.dto.membership.SwitchActiveShopResponse;
import com.inventory.user.rest.dto.user.LinkableUserDto;
import com.inventory.user.service.UserService;
import com.inventory.user.service.UserShopMembershipService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/v1/users")
public class UserShopController {

  @Autowired
  private UserShopMembershipService membershipService;

  @Autowired
  private UserService userService;

  /**
   * Search for a user by email to link to vendor/customer.
   * Returns minimal info (userId, email, name) for identity confirmation.
   * Requires authentication.
   */
  @GetMapping("/search")
  public ResponseEntity<ApiResponse<LinkableUserDto>> searchUserForLinking(
      @RequestParam(required = false) String email,
      HttpServletRequest request) {
    String userId = (String) request.getAttribute("userId");
    if (!StringUtils.hasText(userId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
    if (!StringUtils.hasText(email)) {
      throw new ValidationException("Email is required for user search");
    }
    Optional<LinkableUserDto> result = userService.searchUserByEmailForLinking(email.trim());
    return result
        .map(linkable -> ResponseEntity.ok(ApiResponse.success(linkable)))
        .orElseGet(() -> ResponseEntity.ok(ApiResponse.success(null)));
  }

  /**
   * Get all shops the current user has access to.
   */
  @GetMapping("/me/shops")
  public ResponseEntity<ApiResponse<UserShopListResponse>> getMyShops(HttpServletRequest request) {
    String userId = (String) request.getAttribute("userId");
    if (!StringUtils.hasText(userId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
    return ResponseEntity.ok(ApiResponse.success(membershipService.getShopsForUser(userId)));
  }

  /**
   * Switch the user's active shop. Updates UserAccount.shopId for subsequent requests.
   */
  @PostMapping("/me/active-shop")
  public ResponseEntity<ApiResponse<SwitchActiveShopResponse>> switchActiveShop(
      @RequestBody SwitchActiveShopRequest body,
      HttpServletRequest request) {
    String userId = (String) request.getAttribute("userId");
    if (!StringUtils.hasText(userId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
    if (body == null || !StringUtils.hasText(body.getShopId())) {
      throw new ValidationException("shopId is required");
    }
    membershipService.switchActiveShop(userId, body.getShopId().trim());
    return ResponseEntity.ok(ApiResponse.success(
        new SwitchActiveShopResponse(body.getShopId(), "Active shop switched successfully")));
  }
}
