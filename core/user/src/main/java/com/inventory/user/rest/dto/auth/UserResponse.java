package com.inventory.user.rest.dto.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.rest.dto.invitation.UserShopDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserResponse {
  private String userId;
  private UserRole role;
  private String shopId;  // Active shop (backward compatible)
  private String email;
  private String name;
  private Boolean active;
  private String createdAt;
  private List<UserShopDto> shops;  // All shops user has access to (multi-shop)
}

