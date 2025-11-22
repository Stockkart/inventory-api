package com.inventory.user.rest.dto.auth;

import lombok.Data;

@Data
public class LoginResponse {
  String accessToken;
  String refreshToken;
  UserSummary user;

  @Data
  public static class UserSummary {
    String userId;
    String role;
    String shopId;
  }
}

