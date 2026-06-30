package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomerProductHistoryResponse {
  private Map<String, CustomerProductHistoryGroupDto> bySellableRef;
}
