package com.inventory.user.rest.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserActiveRequest {

  private Boolean active;
  /** Optional; audited. */
  private String reason;
}
