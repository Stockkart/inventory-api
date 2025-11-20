package com.inventory.user.service;

import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserInvite;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.domain.repository.UserInviteRepository;
import com.inventory.user.rest.dto.auth.AcceptInviteRequest;
import com.inventory.user.rest.dto.auth.AcceptInviteResponse;
import com.inventory.user.rest.dto.auth.LoginRequest;
import com.inventory.user.rest.dto.auth.LoginResponse;
import com.inventory.user.rest.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuthService {

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private UserInviteRepository userInviteRepository;

    @Autowired
    private UserMapper userMapper;

    public LoginResponse login(LoginRequest request) {
        UserAccount account = userAccountRepository.findByEmail(request.getEmail())
                .filter(user -> user.getPassword() != null && user.getPassword().equals(request.getPassword()))
                .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));

        return LoginResponse.builder()
                .accessToken(UUID.randomUUID().toString())
                .refreshToken(UUID.randomUUID().toString())
                .user(userMapper.toUserSummary(account))
                .build();
    }

    public AcceptInviteResponse acceptAdminInvite(AcceptInviteRequest request) {
        UserInvite invite = userInviteRepository.findByToken(request.getInviteToken())
                .orElseThrow(() -> new IllegalArgumentException("Invite not found"));
        invite.setAccepted(true);
        userInviteRepository.save(invite);

        UserAccount account = userAccountRepository.findByEmail(invite.getEmail())
                .orElse(UserAccount.builder()
                        .userId("user-" + UUID.randomUUID())
                        .email(invite.getEmail())
                        .shopId(invite.getShopId())
                        .role(invite.getRole())
                        .build());
        account.setName(invite.getName());
        account.setPassword(request.getPassword());
        account.setActive(true);
        account.setInviteAccepted(true);
        userAccountRepository.save(account);

        return AcceptInviteResponse.builder()
                .userId(account.getUserId())
                .role(account.getRole())
                .shopId(account.getShopId())
                .active(account.isActive())
                .build();
    }
}

