package com.inventory.product.rest.dto.shop;

import lombok.Data;

@Data
public class ShopApprovalRequest {
  private boolean approve;
  private Integer userLimit;
}

