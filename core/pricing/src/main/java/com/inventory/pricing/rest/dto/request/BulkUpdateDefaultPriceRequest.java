package com.inventory.pricing.rest.dto.request;

import lombok.Data;

import java.util.List;

@Data
public class BulkUpdateDefaultPriceRequest {
  private List<UpdateDefaultPriceItem> updates;
}
