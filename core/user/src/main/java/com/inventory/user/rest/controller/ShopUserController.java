package com.inventory.user.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.user.rest.dto.request.UpdateUserRequest;
import com.inventory.user.rest.dto.response.DeactivateUserResponse;
import com.inventory.user.rest.dto.response.UserDto;
import com.inventory.user.rest.dto.response.UserListResponse;
import com.inventory.user.service.UserService;
import com.inventory.user.service.UserShopMembershipService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
public class ShopUserController {

  @Autowired
  private UserService userService;

  @Autowired
  private UserShopMembershipService membershipService;

  @GetMapping("/api/v1/shops/{shopId}/users")
  public ResponseEntity<ApiResponse<UserListResponse>> listUsers(@PathVariable String shopId,
                                                                 HttpServletRequest request) {
    validateShopAccess(request, shopId);
    return ResponseEntity.ok(ApiResponse.success(userService.listUsers(shopId)));
  }

  @PatchMapping("/api/v1/shops/{shopId}/users/{userId}")
  public ResponseEntity<ApiResponse<UserDto>> updateUser(@PathVariable String shopId,
                                                         @PathVariable String userId,
                                                         @RequestBody UpdateUserRequest request,
                                                         HttpServletRequest httpRequest) {
    validateShopAccess(httpRequest, shopId);
    return ResponseEntity.ok(ApiResponse.success(userService.updateUser(shopId, userId, request)));
  }

  @DeleteMapping("/api/v1/shops/{shopId}/users/{userId}")
  public ResponseEntity<ApiResponse<DeactivateUserResponse>> deactivate(@PathVariable String shopId,
                                                                        @PathVariable String userId,
                                                                        HttpServletRequest request) {
    validateShopAccess(request, shopId);
    return ResponseEntity.ok(ApiResponse.success(userService.deactivate(shopId, userId)));
  }

  private void validateShopAccess(HttpServletRequest request, String shopId) {
    String userId = (String) request.getAttribute("userId");
    if (!StringUtils.hasText(userId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
    if (!membershipService.hasAccess(userId, shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User does not have access to this shop");
    }
  }
}

