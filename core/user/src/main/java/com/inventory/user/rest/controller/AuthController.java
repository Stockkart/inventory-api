package com.inventory.user.rest.controller;

import com.inventory.user.rest.dto.auth.AcceptInviteRequest;
import com.inventory.user.rest.dto.auth.AcceptInviteResponse;
import com.inventory.user.rest.dto.auth.LoginRequest;
import com.inventory.user.rest.dto.auth.LoginResponse;
import com.inventory.user.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/accept-invite")
    public ResponseEntity<AcceptInviteResponse> acceptInvite(@RequestBody AcceptInviteRequest request) {
        return ResponseEntity.ok(authService.acceptAdminInvite(request));
    }
}

