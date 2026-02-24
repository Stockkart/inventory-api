package com.inventory.pricing.rest.dto;

import com.inventory.pricing.domain.model.Rate;
import lombok.Data;

import java.util.List;

/**
 * Single item in bulk update: pricing ID + rates and/or defaultRate.
 */
@Data
public class UpdateDefaultPriceItem {
  private String pricingId;
  private List<Rate> rates;
  private String defaultRate;
}
