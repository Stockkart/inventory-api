package com.inventory.product.rest.dto.business;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class BusinessTypeResponse {
  String id;
  String name;
  boolean enabled;
}

