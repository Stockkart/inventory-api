package com.inventory.product.rest.dto.request;

import java.util.List;
import lombok.Data;

@Data
public class VoidKotRequest {

  private List<String> lineIds;
  private String reason;
}
