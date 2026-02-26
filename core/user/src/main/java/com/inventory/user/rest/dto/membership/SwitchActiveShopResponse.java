package com.inventory.user.rest.dto.membership;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SwitchActiveShopResponse {
  private String activeShopId;
  private String message;
}
