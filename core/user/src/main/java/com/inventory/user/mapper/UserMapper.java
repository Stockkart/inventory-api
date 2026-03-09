package com.inventory.user.mapper;

import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.model.UserToken;
import com.inventory.user.rest.dto.request.SignupRequest;
import com.inventory.user.rest.dto.response.DeactivateUserResponse;
import com.inventory.user.rest.dto.response.LinkableUserDto;
import com.inventory.user.rest.dto.response.LoginResponse;
import com.inventory.user.rest.dto.response.LogoutResponse;
import com.inventory.user.rest.dto.response.SignupResponse;
import com.inventory.user.rest.dto.response.UserDto;
import com.inventory.user.rest.dto.response.UserListResponse;
import com.inventory.user.rest.dto.response.UserResponse;
import com.inventory.user.service.ShopServiceAdapter;
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

  default UserDto toUserDtoWithRole(UserAccount account, UserRole role) {
    UserDto dto = toDto(account);
    dto.setRole(role != null ? role : account.getRole());
    return dto;
  }

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

  // SignupRequest to UserAccount mapping
  @Mapping(target = "name", source = "name")
  @Mapping(target = "email", source = "email")
  @Mapping(target = "role", source = "role")
  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "active", constant = "true")
  @Mapping(target = "inviteAccepted", constant = "false")
  @Mapping(target = "password", ignore = true)
  @Mapping(target = "userId", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  UserAccount toUserAccount(SignupRequest request, @Context PasswordEncoder passwordEncoder);

  @AfterMapping
  default void setUserAccountGeneratedFields(@MappingTarget UserAccount account, SignupRequest request, @Context PasswordEncoder passwordEncoder) {
    // MongoDB will auto-generate the userId as ObjectId
    // Set default role to OWNER if not provided
    if (account.getRole() == null) {
      account.setRole(UserRole.OWNER);
    }
    account.setPassword(passwordEncoder.encode(request.getPassword()));
    Instant now = Instant.now();
    account.setCreatedAt(now);
    account.setUpdatedAt(now);
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

  LoginResponse.ShopInfo toShopInfo(ShopServiceAdapter.ShopTaxInfo taxInfo);

  default UserAccount toUserAccountFromOAuth(String email, String name, SignupRequest request) {
    if (email == null) {
      return null;
    }
    UserAccount account = new UserAccount();
    account.setEmail(email);
    account.setName(name != null && !name.isBlank() ? name : email.split("@")[0]);
    account.setRole(request != null && request.getRole() != null ? request.getRole() : UserRole.OWNER);
    account.setShopId(request != null ? request.getShopId() : null);
    account.setActive(true);
    account.setInviteAccepted(false);
    Instant now = Instant.now();
    account.setCreatedAt(now);
    account.setUpdatedAt(now);
    account.setPassword(null);
    return account;
  }

  default UserListResponse toUserListResponse(java.util.List<UserDto> data) {
    if (data == null) {
      return null;
    }
    UserListResponse r = new UserListResponse();
    r.setData(data);
    return r;
  }

  default DeactivateUserResponse toDeactivateUserResponse(String userId, boolean active) {
    DeactivateUserResponse r = new DeactivateUserResponse();
    r.setUserId(userId);
    r.setActive(active);
    return r;
  }

  default LinkableUserDto toLinkableUserDto(UserAccount account) {
    if (account == null) {
      return null;
    }
    return new LinkableUserDto(account.getUserId(), account.getEmail(), account.getName());
  }
}
