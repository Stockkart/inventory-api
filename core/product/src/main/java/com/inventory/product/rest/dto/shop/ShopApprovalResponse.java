package com.inventory.product.rest.dto.shop;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ShopApprovalResponse {
    String shopId;
    boolean active;
    Integer userLimit;
    Integer userCount;
}

