package com.inventory.product.service;

import com.inventory.product.rest.dto.inventory.CreateInventoryPricingRequest;
import com.inventory.product.rest.dto.inventory.InventoryPricingDto;

import java.util.List;
import java.util.Map;

public interface InventoryPricingAdapter {

  String createOrUpdatePricing(CreateInventoryPricingRequest request);

  boolean pricingExists(String pricingId);

  Map<String, InventoryPricingDto> getPricingBulk(List<String> pricingIds);
}
