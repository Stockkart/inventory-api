package com.inventory.user.rest.dto.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.inventory.user.domain.model.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserResponse {
  private String userId;
  private UserRole role;
  private String shopId;
  private String email;
  private String name;
  private Boolean active;
  private String createdAt;
}

