package com.inventory.user.rest.controller;

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
  public ResponseEntity<UserListResponse> listUsers(@PathVariable String shopId) {
    return ResponseEntity.ok(userService.listUsers(shopId));
  }

  @PostMapping("/api/v1/shops/{shopId}/users")
  public ResponseEntity<AddUserResponse> addUser(@PathVariable String shopId,
                                                 @RequestBody AddUserRequest request) {
    return ResponseEntity.ok(userService.addUser(shopId, request));
  }

  @PostMapping("/api/v1/users/{inviteId}/accept")
  public ResponseEntity<UserDto> acceptInvite(@PathVariable String inviteId,
                                              @RequestBody AcceptUserInviteRequest request) {
    return ResponseEntity.ok(userService.acceptInvite(inviteId, request));
  }

  @PatchMapping("/api/v1/shops/{shopId}/users/{userId}")
  public ResponseEntity<UserDto> updateUser(@PathVariable String shopId,
                                            @PathVariable String userId,
                                            @RequestBody UpdateUserRequest request) {
    return ResponseEntity.ok(userService.updateUser(shopId, userId, request));
  }

  @DeleteMapping("/api/v1/shops/{shopId}/users/{userId}")
  public ResponseEntity<DeactivateUserResponse> deactivate(@PathVariable String shopId,
                                                           @PathVariable String userId) {
    return ResponseEntity.ok(userService.deactivate(shopId, userId));
  }
}

