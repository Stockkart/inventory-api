package com.inventory.product.rest.dto.sale;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SaleStatusResponse {
    String saleId;
    boolean valid;
}

