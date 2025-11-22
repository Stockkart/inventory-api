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
import org.mapstruct.factory.Mappers;

import java.time.Instant;

@Mapper(componentModel = "spring", 
        unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface UserMapper {

    UserMapper INSTANCE = Mappers.getMapper(UserMapper.class);

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
    @Mapping(target = "inviteId", source = "inviteId")
    @Mapping(target = "email", source = "email")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "role", source = "role")
    @Mapping(target = "shopId", source = "shopId")
    @Mapping(target = "expiresAt", source = "expiresAt")
    @Mapping(target = "accepted", source = "accepted")
    UserInviteDto toUserInviteDto(UserInvite invite);
    
    // DTO to Entity mappings with manual implementation for complex mappings
    @Mapping(target = "email", source = "invite.email")
    @Mapping(target = "name", source = "invite.name")
    @Mapping(target = "role", source = "invite.role")
    @Mapping(target = "shopId", source = "invite.shopId")
    @Mapping(target = "password", source = "password")
    @Mapping(target = "active", constant = "true")
    @Mapping(target = "inviteAccepted", constant = "true")
    @Mapping(target = "userId", ignore = true)
    UserAccount toUserAccount(UserInvite invite, String password);
    
    @Mapping(target = "email", source = "email")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "role", source = "role")
    @Mapping(target = "shopId", source = "shopId")
    @Mapping(target = "accepted", constant = "false")
    @Mapping(target = "inviteId", ignore = true)
    @Mapping(target = "expiresAt", ignore = true)
    UserInvite toUserInvite(String email, String name, String role, String shopId);
}

