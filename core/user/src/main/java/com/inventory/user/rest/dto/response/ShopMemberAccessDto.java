package com.inventory.user.rest.dto.response;

import com.inventory.user.domain.model.MemberPermissions;
import com.inventory.user.domain.model.UserRole;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopMemberAccessDto {
  private String userId;
  private String name;
  private String email;
  private UserRole role;
  private String relationship;
  private boolean active;
  private Instant joinedAt;
  private MemberPermissions permissions;
  private ShopAccessResponse effectiveAccess;
}
