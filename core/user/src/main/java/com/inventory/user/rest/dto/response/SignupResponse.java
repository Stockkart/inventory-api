package com.inventory.user.rest.dto.response;

import com.inventory.user.domain.model.UserRole;
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
    UserRole role;
    String shopId;
    String email;
    String name;
    Boolean active;
    Instant createdAt;
  }
}
