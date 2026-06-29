package com.inventory.user.rest.dto.request;

import com.inventory.user.domain.model.MemberModulePermissions;
import java.util.Set;
import lombok.Data;

@Data
public class UpdateMemberPermissionsRequest {
  private MemberModulePermissions modules;
  private Set<String> productSearchEditableFields;
}
