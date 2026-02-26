package com.inventory.user.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.ValidationException;
import com.inventory.user.rest.dto.invitation.UserShopListResponse;
import com.inventory.user.rest.dto.membership.SwitchActiveShopRequest;
import com.inventory.user.rest.dto.membership.SwitchActiveShopResponse;
import com.inventory.user.service.UserShopMembershipService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserShopController {

  @Autowired
  private UserShopMembershipService membershipService;

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
