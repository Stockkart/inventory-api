package com.inventory.pricing.service;

import com.inventory.pricing.rest.dto.PricingDto;
import com.inventory.product.domain.model.Rate;
import com.inventory.product.rest.dto.inventory.CreateInventoryPricingRequest;
import com.inventory.product.rest.dto.inventory.InventoryPricingDto;
import com.inventory.product.service.InventoryPricingAdapter;

import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.rest.dto.CreatePricingRequest;
import com.inventory.pricing.rest.mapper.PricingMapper;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class InventoryPricingAdapterImpl implements InventoryPricingAdapter {

  @Autowired
  private  PricingService pricingService;

  @Autowired
  private  PricingMapper pricingMapper;

  @Override
  public String createOrUpdatePricing(CreateInventoryPricingRequest request) {

    CreatePricingRequest pricingRequest =
      pricingMapper.fromProductRequest(request);

    Pricing pricing = pricingService.createOrUpdate(pricingRequest);

    return pricing.getId();
  }

  @Override
  public Map<String, InventoryPricingDto> getPricingBulk(List<String> pricingIds) {

    if (pricingIds == null || pricingIds.isEmpty()) {
      return Collections.emptyMap();
    }

    return pricingService.getByIdIn(pricingIds)
      .stream()
      .collect(Collectors.toMap(
        Pricing::getId,
        pricing -> toProductDto(pricingMapper.toDto(pricing))
      ));
  }

  @Override
  public boolean pricingExists(String pricingId) {
    return pricingService.exists(pricingId);
  }

  private InventoryPricingDto toProductDto(PricingDto pricing) {

    if (pricing == null) return null;

    // Fix: Map List<Pricing.Rate> to List<Product.Rate>
    List<com.inventory.product.domain.model.Rate> productRates = null;

    if (pricing.getRates() != null) {
      productRates = pricing.getRates().stream()
        .map(pricingRate -> {
          // Create new Product Rate instance
          Rate productRate = new Rate();

          // Map fields (assuming these getters/setters exist)
          productRate.setPrice(pricingRate.getPrice());
          productRate.setName(pricingRate.getName());

          return productRate;
        })
        .collect(Collectors.toList());
    }

    return InventoryPricingDto.builder()
      .id(pricing.getId())
      .maximumRetailPrice(pricing.getMaximumRetailPrice())
      .costPrice(pricing.getCostPrice())
      .sellingPrice(pricing.getSellingPrice())
      .additionalDiscount(pricing.getAdditionalDiscount())
      .sgst(pricing.getSgst())
      .cgst(pricing.getCgst())
      .rates(productRates)   // Now safe (DTO → DTO)
      .setPrice(pricing.getDefaultPrice())
      .build();
  }
}
