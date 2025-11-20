package com.inventory.user.rest.controller;

import com.inventory.user.rest.dto.user.AcceptUserInviteRequest;
import com.inventory.user.rest.dto.user.AddUserRequest;
import com.inventory.user.rest.dto.user.AddUserResponse;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

