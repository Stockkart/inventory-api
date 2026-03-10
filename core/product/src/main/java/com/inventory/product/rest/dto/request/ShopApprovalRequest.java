package com.inventory.product.rest.dto.request;

import lombok.Data;

@Data
public class ShopApprovalRequest {
  private boolean approve;
  private Integer userLimit;
}

