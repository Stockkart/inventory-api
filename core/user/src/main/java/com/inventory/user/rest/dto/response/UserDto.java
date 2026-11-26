package com.inventory.user.rest.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.inventory.user.domain.model.UserRole;
import lombok.Data;

@Data
public class UserDto {
  String userId;
  String name;
  String email;
  UserRole role;
  boolean active;
  /**
   * For a WORKER member: the code of the shop's employee this login is linked to (worker-app
   * R2.5), so the Users page's "Workers" group can show it. Null for staff rows and for a worker
   * with no linked employee.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  String linkedEmployeeCode;
}
