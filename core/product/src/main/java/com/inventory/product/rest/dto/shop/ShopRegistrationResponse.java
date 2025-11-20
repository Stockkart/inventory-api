package com.inventory.product.rest.dto.shop;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ShopRegistrationResponse {
    String shopId;
    String status;
}

