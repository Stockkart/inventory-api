package com.inventory.user.rest.dto.auth;

import lombok.Data;

import java.time.Instant;

@Data
public class SignupResponse {
  String accessToken;
  String refreshToken;
  UserSummary user;

  @Data
  public static class UserSummary {
    String userId;
    String role;
    String shopId;
    String email;
    String name;
    Boolean active;
    Instant createdAt;
  }
}

