package com.inventory.user.rest.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.inventory.user.domain.model.PlatformRole;
import com.inventory.user.domain.model.UserRole;
import lombok.Data;

import java.time.Instant;
import java.util.Set;

@Data
public class LoginResponse {
  String accessToken;
  String refreshToken;
  UserSummary user;
  ShopInfo shop;

  @Data
  public static class UserSummary {
    String userId;
    UserRole role;
    String shopId;
    String email;
    String name;
    String phone;
    Boolean active;
    Instant createdAt;
    /** Omitted for ordinary users; lets the UI show platform admin pages. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    Set<PlatformRole> platformRoles;
  }

  @Data
  public static class ShopInfo {
    String sgst;
    String cgst;
    String name;
  }
}
