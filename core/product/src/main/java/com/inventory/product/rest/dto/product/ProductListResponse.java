package com.inventory.product.rest.dto.product;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class ProductListResponse {
    @Singular("product")
    List<ProductResponse> data;
}

