package com.inventory.user.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.user.rest.dto.user.DeactivateUserResponse;
import com.inventory.user.rest.dto.user.UpdateUserRequest;
import com.inventory.user.rest.dto.user.UserDto;
import com.inventory.user.rest.dto.user.UserListResponse;
import com.inventory.user.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
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

  @GetMapping("/api/v1/shops/{shopId}/users")
  public ResponseEntity<ApiResponse<UserListResponse>> listUsers(@PathVariable String shopId) {
    return ResponseEntity.ok(ApiResponse.success(userService.listUsers(shopId)));
  }

  @PatchMapping("/api/v1/shops/{shopId}/users/{userId}")
  public ResponseEntity<ApiResponse<UserDto>> updateUser(@PathVariable String shopId,
                                                         @PathVariable String userId,
                                                         @RequestBody UpdateUserRequest request) {
    return ResponseEntity.ok(ApiResponse.success(userService.updateUser(shopId, userId, request)));
  }

  @DeleteMapping("/api/v1/shops/{shopId}/users/{userId}")
  public ResponseEntity<ApiResponse<DeactivateUserResponse>> deactivate(@PathVariable String shopId,
                                                                        @PathVariable String userId) {
    return ResponseEntity.ok(ApiResponse.success(userService.deactivate(shopId, userId)));
  }
}

