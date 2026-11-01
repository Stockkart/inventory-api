package com.inventory.user.mapper;

import com.inventory.user.domain.model.AdminUser;
import com.inventory.user.rest.dto.response.AdminUserResponse;

public final class AdminUserMapper {

  private AdminUserMapper() {
  }

  public static AdminUserResponse toResponse(AdminUser admin) {
    return AdminUserResponse.builder()
        .id(admin.getId())
        .email(admin.getEmail())
        .name(admin.getName())
        .active(admin.isActive())
        .mustChangePassword(admin.isMustChangePassword())
        .createdByAdminId(admin.getCreatedByAdminId())
        .createdAt(admin.getCreatedAt())
        .lastLoginAt(admin.getLastLoginAt())
        .build();
  }
}
