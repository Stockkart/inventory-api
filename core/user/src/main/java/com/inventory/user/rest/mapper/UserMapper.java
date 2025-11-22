package com.inventory.user.rest.mapper;

import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserInvite;
import com.inventory.user.rest.dto.auth.AcceptInviteResponse;
import com.inventory.user.rest.dto.auth.LoginResponse;
import com.inventory.user.rest.dto.user.UserDto;
import com.inventory.user.rest.dto.user.UserInviteDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

import java.time.Instant;

@Mapper(componentModel = "spring", 
        unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface UserMapper {

    // UserAccount to DTO mappings
    UserDto toDto(UserAccount account);
    
    @Mapping(target = "userId", source = "userId")
    @Mapping(target = "role", source = "role")
    @Mapping(target = "shopId", source = "shopId")
    LoginResponse.UserSummary toUserSummary(UserAccount user);
    
    @Mapping(target = "userId", source = "userId")
    @Mapping(target = "role", source = "role")
    @Mapping(target = "shopId", source = "shopId")
    @Mapping(target = "active", source = "active")
    AcceptInviteResponse toAcceptInviteResponse(UserAccount account);
    
    // UserInvite to DTO mappings
    default UserInviteDto toUserInviteDto(UserInvite invite) {
        if (invite == null) {
            return null;
        }
        return UserInviteDto.builder()
                .inviteId(invite.getInviteId())
                .email(invite.getEmail())
                .name(invite.getName())
                .role(invite.getRole())
                .shopId(invite.getShopId())
                .expiresAt(invite.getExpiresAt())
                .accepted(invite.isAccepted())
                .build();
    }
    
    // DTO to Entity mappings with manual implementation for complex mappings
    default UserAccount toUserAccount(UserInvite invite, String password) {
        if (invite == null) {
            return null;
        }
        
        return UserAccount.builder()
                .email(invite.getEmail())
                .name(invite.getName())
                .role(invite.getRole())
                .shopId(invite.getShopId())
                .password(password)
                .active(true)
                .inviteAccepted(true)
                .build();
    }
    
    default UserInvite toUserInvite(String email, String name, String role, String shopId) {
        return UserInvite.builder()
                .email(email)
                .name(name)
                .role(role)
                .shopId(shopId)
                .accepted(false)
                .build();
    }
}

