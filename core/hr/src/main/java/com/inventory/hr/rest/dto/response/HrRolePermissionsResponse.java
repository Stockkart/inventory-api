package com.inventory.hr.rest.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HrRolePermissionsResponse {
  /** Effective row per grid role (stored, else default), permission names in enum order. */
  private Map<String, List<String>> roles;
  /** Dependency rules the grid auto-ticks: permission to the permissions it needs. */
  private Map<String, List<String>> requires;
  /** null = never saved (all rows are the defaults). */
  private Instant updatedAt;
}
