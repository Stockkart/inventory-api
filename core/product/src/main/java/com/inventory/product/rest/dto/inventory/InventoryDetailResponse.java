package com.inventory.product.rest.dto.inventory;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class InventoryDetailResponse {
    String lotId;
    String productId;
    Integer receivedCount;
    Integer soldCount;
    Integer currentCount;
    String location;
    Instant expiryDate;
    String shopId;
}

