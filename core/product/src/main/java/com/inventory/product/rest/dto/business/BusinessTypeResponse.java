package com.inventory.product.rest.dto.business;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BusinessTypeResponse {
  String id;
  String name;
  boolean enabled;
}

