package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShopApprovalResponse {
  String shopId;
  boolean active;
  Integer userLimit;
  Integer userCount;
}

