package com.inventory.user.rest.dto.auth;

import com.inventory.user.domain.model.UserRole;
import lombok.Data;

import java.time.Instant;

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
    Boolean active;
    Instant createdAt;
  }

  @Data
  public static class ShopInfo {
    String sgst;
    String cgst;
  }
}

