package com.inventory.user.rest.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserReasonRequest {

  /** Optional; audited. */
  private String reason;
}
