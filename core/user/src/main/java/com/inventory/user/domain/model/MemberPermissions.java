package com.inventory.user.domain.model;

import java.util.LinkedHashSet;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemberPermissions {
  private MemberModulePermissions modules;
  /** Used when shop policy is PERMISSION_BASED. Empty = view-only product search edits. */
  private Set<String> productSearchEditableFields = new LinkedHashSet<>();
}
