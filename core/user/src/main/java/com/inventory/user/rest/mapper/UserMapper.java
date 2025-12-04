package com.inventory.user.rest.mapper;

import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserInvite;
import com.inventory.user.domain.model.UserToken;
import com.inventory.user.rest.dto.auth.AcceptInviteResponse;
import com.inventory.user.rest.dto.auth.LoginResponse;
import com.inventory.user.rest.dto.auth.LogoutResponse;
import com.inventory.user.rest.dto.auth.SignupRequest;
import com.inventory.user.rest.dto.auth.SignupResponse;
import com.inventory.user.rest.dto.auth.UserResponse;
import com.inventory.user.rest.dto.user.UserDto;
import com.inventory.user.rest.dto.user.UserInviteDto;
import org.mapstruct.AfterMapping;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.UUID;

@Mapper(componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface UserMapper {

  UserMapper INSTANCE = Mappers.getMapper(UserMapper.class);

  // UserAccount to DTO mappings
  UserDto toDto(UserAccount account);

  @Mapping(target = "userId", source = "userId")
  @Mapping(target = "role", source = "role")
  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "email", source = "email")
  @Mapping(target = "name", source = "name")
  @Mapping(target = "active", source = "active")
  @Mapping(target = "createdAt", source = "updatedAt")
  LoginResponse.UserSummary toUserSummary(UserAccount user);

  @Mapping(target = "accessToken", ignore = true)
  @Mapping(target = "refreshToken", ignore = true)
  @Mapping(target = "user", ignore = true)
  LoginResponse toLoginResponse(UserAccount account, @Context String deviceId);

  @AfterMapping
  default void mapUserToLoginResponse(@MappingTarget LoginResponse response, UserAccount account) {
    response.setUser(toUserSummary(account));
  }

  @Mapping(target = "userId", source = "userId")
  @Mapping(target = "role", source = "role")
  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "active", source = "active")
  AcceptInviteResponse toAcceptInviteResponse(UserAccount account);

  @Mapping(target = "userId", source = "userId")
  @Mapping(target = "role", source = "role")
  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "email", source = "email")
  @Mapping(target = "name", source = "name")
  @Mapping(target = "active", source = "active")
  @Mapping(target = "createdAt", source = "updatedAt")
  SignupResponse.UserSummary toSignupUserSummary(UserAccount user);

  @Mapping(target = "accessToken", ignore = true)
  @Mapping(target = "refreshToken", ignore = true)
  @Mapping(target = "user", ignore = true)
  SignupResponse toSignupResponse(UserAccount account, @Context String deviceId);

  @AfterMapping
  default void mapUserToSignupResponse(@MappingTarget SignupResponse response, UserAccount account) {
    response.setUser(toSignupUserSummary(account));
  }

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
  @Mapping(target = "password", ignore = true)
  @Mapping(target = "active", constant = "true")
  @Mapping(target = "inviteAccepted", constant = "true")
  @Mapping(target = "userId", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  UserAccount toUserAccount(UserInvite invite, @Context PasswordEncoder passwordEncoder, String rawPassword);

  @AfterMapping
  default void setUserAccountFromInviteFields(@MappingTarget UserAccount account, UserInvite invite, @Context PasswordEncoder passwordEncoder, String rawPassword) {
    if (account.getUserId() == null) {
      account.setUserId("user-" + UUID.randomUUID());
    }
    account.setPassword(passwordEncoder.encode(rawPassword));
    account.setUpdatedAt(Instant.now());
  }

  // Update existing UserAccount from UserInvite
  @Mapping(target = "name", source = "invite.name")
  @Mapping(target = "password", ignore = true)
  @Mapping(target = "active", constant = "true")
  @Mapping(target = "inviteAccepted", constant = "true")
  @Mapping(target = "updatedAt", ignore = true)
  @Mapping(target = "userId", ignore = true)
  @Mapping(target = "email", ignore = true)
  @Mapping(target = "role", ignore = true)
  @Mapping(target = "shopId", ignore = true)
  void updateUserAccountFromInvite(@MappingTarget UserAccount account, UserInvite invite, @Context PasswordEncoder passwordEncoder, String rawPassword);

  @AfterMapping
  default void setUpdatedAccountFields(@MappingTarget UserAccount account, UserInvite invite, @Context PasswordEncoder passwordEncoder, String rawPassword) {
    account.setPassword(passwordEncoder.encode(rawPassword));
    account.setUpdatedAt(Instant.now());
  }

  @Mapping(target = "email", source = "email")
  @Mapping(target = "name", source = "name")
  @Mapping(target = "role", source = "role")
  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "accepted", constant = "false")
  @Mapping(target = "inviteId", ignore = true)
  @Mapping(target = "expiresAt", ignore = true)
  UserInvite toUserInvite(String email, String name, String role, String shopId);

  // SignupRequest to UserAccount mapping
  @Mapping(target = "name", source = "name")
  @Mapping(target = "email", source = "email")
  @Mapping(target = "role", source = "role", defaultValue = "CASHIER")
  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "active", constant = "true")
  @Mapping(target = "inviteAccepted", constant = "false")
  @Mapping(target = "password", ignore = true)
  @Mapping(target = "userId", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  UserAccount toUserAccount(SignupRequest request, @Context PasswordEncoder passwordEncoder);

  @AfterMapping
  default void setUserAccountGeneratedFields(@MappingTarget UserAccount account, SignupRequest request, @Context PasswordEncoder passwordEncoder) {
    account.setUserId("user-" + UUID.randomUUID());
    account.setPassword(passwordEncoder.encode(request.getPassword()));
    account.setUpdatedAt(Instant.now());
  }

  @AfterMapping
  default void setLoginResponseTokens(@MappingTarget LoginResponse response, UserAccount account, @Context String deviceId) {
    String accessToken = UUID.randomUUID().toString();
    String refreshToken = UUID.randomUUID().toString();
    response.setAccessToken(accessToken);
    response.setRefreshToken(refreshToken);
    
    // Save token to UserAccount for multi-device support
    UserToken token = new UserToken();
    token.setAccessToken(accessToken);
    token.setRefreshToken(refreshToken);
    token.setDeviceId(deviceId != null && !deviceId.trim().isEmpty()
            ? deviceId : "device-" + java.util.UUID.randomUUID());
    token.setCreatedAt(Instant.now());
    token.setExpiresAt(Instant.now().plusSeconds(7 * 24 * 60 * 60)); // 7 days
    
    account.getTokens().add(token);
  }

  @AfterMapping
  default void setSignupResponseTokens(@MappingTarget SignupResponse response, UserAccount account, @Context String deviceId) {
    String accessToken = UUID.randomUUID().toString();
    String refreshToken = UUID.randomUUID().toString();
    response.setAccessToken(accessToken);
    response.setRefreshToken(refreshToken);
    
    // Save token to UserAccount for multi-device support
    UserToken token = new UserToken();
    token.setAccessToken(accessToken);
    token.setRefreshToken(refreshToken);
    token.setDeviceId(deviceId != null && !deviceId.trim().isEmpty()
            ? deviceId : "device-" + java.util.UUID.randomUUID());
    token.setCreatedAt(Instant.now());
    token.setExpiresAt(Instant.now().plusSeconds(7 * 24 * 60 * 60)); // 7 days
    
    account.getTokens().add(token);
  }

  // LogoutResponse mapping
  @Mapping(target = "message", ignore = true)
  @Mapping(target = "success", ignore = true)
  @Mapping(target = "deviceId", source = "deviceId")
  LogoutResponse toLogoutResponse(String deviceId);

  @AfterMapping
  default void setLogoutResponseFields(@MappingTarget LogoutResponse response, String deviceId) {
    response.setMessage("Logged out successfully");
    response.setSuccess(true);
  }

  // UserResponse mapping
  @Mapping(target = "userId", source = "userId")
  @Mapping(target = "role", source = "role")
  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "email", source = "email")
  @Mapping(target = "name", source = "name")
  @Mapping(target = "active", source = "active")
  @Mapping(target = "createdAt", ignore = true)
  UserResponse toUserResponse(UserAccount account);

  @AfterMapping
  default void setUserResponseCreatedAt(@MappingTarget UserResponse response, UserAccount account) {
    // Use updatedAt as createdAt if createdAt doesn't exist, or format it as ISO string
    if (account.getUpdatedAt() != null) {
      response.setCreatedAt(account.getUpdatedAt().toString());
    }
  }
}
