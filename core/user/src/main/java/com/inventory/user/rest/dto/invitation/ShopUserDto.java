package com.inventory.user.rest.dto.invitation;

import com.inventory.user.domain.model.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopUserDto {
  private String userId;
  private String name;
  private String email;
  private UserRole role;
  private String relationship; // OWNER or INVITED
  private boolean active;
  private Instant joinedAt;
}

