package com.inventory.user.service;

import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserInvite;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.domain.repository.UserInviteRepository;
import com.inventory.user.rest.dto.user.AcceptUserInviteRequest;
import com.inventory.user.rest.dto.user.AddUserRequest;
import com.inventory.user.rest.dto.user.AddUserResponse;
import com.inventory.user.rest.dto.user.DeactivateUserResponse;
import com.inventory.user.rest.dto.user.UpdateUserRequest;
import com.inventory.user.rest.dto.user.UserDto;
import com.inventory.user.rest.dto.user.UserListResponse;
import com.inventory.user.rest.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserAccountRepository userAccountRepository;
    private final UserInviteRepository userInviteRepository;
    private final UserMapper userMapper;

    public UserListResponse listUsers(String shopId) {
        List<UserDto> users = userAccountRepository.findByShopId(shopId).stream()
                .map(userMapper::toDto)
                .toList();
        return UserListResponse.builder()
                .data(users)
                .build();
    }

    public AddUserResponse addUser(String shopId, AddUserRequest request) {
        UserInvite invite = UserInvite.builder()
                .inviteId("invite-" + UUID.randomUUID())
                .shopId(shopId)
                .name(request.getName())
                .email(request.getEmail())
                .role(request.getRole())
                .token(UUID.randomUUID().toString())
                .expiresAt(Instant.now().plusSeconds(7 * 24 * 3600))
                .accepted(false)
                .build();
        userInviteRepository.save(invite);
        return AddUserResponse.builder()
                .inviteId(invite.getInviteId())
                .message("Invite sent")
                .build();
    }

    public UserDto acceptInvite(String inviteId, AcceptUserInviteRequest request) {
        UserInvite invite = userInviteRepository.findById(inviteId)
                .orElseThrow(() -> new IllegalArgumentException("Invite not found"));
        invite.setAccepted(true);
        userInviteRepository.save(invite);

        UserAccount account = UserAccount.builder()
                .userId("user-" + UUID.randomUUID())
                .name(invite.getName())
                .role(invite.getRole())
                .shopId(invite.getShopId())
                .email(invite.getEmail())
                .password(request.getPassword())
                .active(true)
                .inviteAccepted(true)
                .build();
        userAccountRepository.save(account);
        return userMapper.toDto(account);
    }

    public UserDto updateUser(String shopId, String userId, UpdateUserRequest request) {
        UserAccount account = userAccountRepository.findById(userId)
                .filter(user -> shopId.equals(user.getShopId()))
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (request.getName() != null) {
            account.setName(request.getName());
        }
        if (request.getRole() != null) {
            account.setRole(request.getRole());
        }
        if (request.getActive() != null) {
            account.setActive(request.getActive());
        }
        userAccountRepository.save(account);
        return userMapper.toDto(account);
    }

    public DeactivateUserResponse deactivate(String shopId, String userId) {
        UserAccount account = userAccountRepository.findById(userId)
                .filter(user -> shopId.equals(user.getShopId()))
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        account.setActive(false);
        userAccountRepository.save(account);
        return DeactivateUserResponse.builder()
                .userId(account.getUserId())
                .active(account.isActive())
                .build();
    }
}

