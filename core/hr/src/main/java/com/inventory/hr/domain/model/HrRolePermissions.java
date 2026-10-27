package com.inventory.hr.domain.model;

import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.user.domain.model.UserRole;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The shop's HR role grid (R1.3), unique {@code shopId}. Keys are ADMIN, MANAGER and CASHIER; a
 * missing key uses the R1.4 default row. OWNER is never stored (always every permission). Every
 * stored row is closed under {@link HrPermission#requires()}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "hr_role_permissions")
public class HrRolePermissions {

  @Id private String id;
  private String shopId;
  private Map<UserRole, List<HrPermission>> roles = new LinkedHashMap<>();
  /**
   * Every permission that existed when the grid was last saved. A permission missing here was
   * added later, so a stored row takes its role default for it ({@code HrAccessService.rowFor}).
   * null = saved before this field existed: the original ten (everything but SELF_CHECK_IN).
   */
  private List<HrPermission> knownPermissions;
  private Instant createdAt;
  private Instant updatedAt;
  private String updatedByUserId;
}
