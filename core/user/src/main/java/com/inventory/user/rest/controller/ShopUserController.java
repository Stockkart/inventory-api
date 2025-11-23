package com.inventory.user.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.user.rest.dto.user.*;
import com.inventory.user.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping
public class ShopUserController {

  @Autowired
  private UserService userService;

  @GetMapping("/api/v1/shops/{shopId}/users")
  public ResponseEntity<ApiResponse<UserListResponse>> listUsers(@PathVariable String shopId) {
    return ResponseEntity.ok(ApiResponse.success(userService.listUsers(shopId)));
  }

  @PostMapping("/api/v1/shops/{shopId}/users")
  public ResponseEntity<ApiResponse<AddUserResponse>> addUser(@PathVariable String shopId,
                                                 @RequestBody AddUserRequest request) {
    return ResponseEntity.ok(ApiResponse.success(userService.addUser(shopId, request)));
  }

  @PostMapping("/api/v1/users/{inviteId}/accept")
  public ResponseEntity<ApiResponse<UserDto>> acceptInvite(@PathVariable String inviteId,
                                              @RequestBody AcceptUserInviteRequest request) {
    return ResponseEntity.ok(ApiResponse.success(userService.acceptInvite(inviteId, request)));
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

