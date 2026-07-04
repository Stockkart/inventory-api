package com.inventory.pluginengine.cart;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QuotationCreateResult {
  private String tokenNo;
}
