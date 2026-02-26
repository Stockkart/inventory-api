package com.inventory.user.rest.dto.joinrequest;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OwnerShopDto {
  private String shopId;
  private String shopName;
}
