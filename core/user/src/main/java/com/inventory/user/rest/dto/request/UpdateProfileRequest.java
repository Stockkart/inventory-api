package com.inventory.user.rest.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code PUT /api/v1/users/me/profile} (worker-app R8.2, R1.11): the caller's own name and
 * phone. {@code phone} is required; {@code name} is optional, and absent or blank means "keep the
 * current name" (the Stockkart Team app's phone step sends {@code {phone}} alone, DD20).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProfileRequest {
  private String name;
  private String phone;
}
