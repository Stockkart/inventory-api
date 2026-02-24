package com.inventory.pricing.rest.dto;

import com.inventory.pricing.domain.model.Rate;
import lombok.Data;

import java.util.List;

/**
 * Request to update pricing. Both rates and defaultRate can be updated.
 * priceToRetail is immutable. sellingPrice is recomputed when rates or defaultRate change.
 */
@Data
public class UpdateDefaultPriceRequest {
  private List<Rate> rates;
  private String defaultRate;
}
