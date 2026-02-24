package com.inventory.pricing.rest.dto;

import lombok.Data;

import java.util.List;

/**
 * Bulk update of default price by pricing ID.
 */
@Data
public class BulkUpdateDefaultPriceRequest {
  private List<UpdateDefaultPriceItem> updates;
}
