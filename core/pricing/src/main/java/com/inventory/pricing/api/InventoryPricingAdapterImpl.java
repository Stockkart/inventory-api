package com.inventory.pricing.api;

import com.inventory.pricing.api.dto.PricingCreateCommand;
import com.inventory.pricing.api.dto.PricingReadDto;
import com.inventory.pricing.api.dto.PricingUpdateCommand;
import com.inventory.pricing.api.dto.RateDto;
import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.rest.dto.CreatePricingRequest;
import com.inventory.pricing.rest.dto.UpdatePricingRequest;
import com.inventory.pricing.service.PricingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Adapter implementing InventoryPricingPort. Delegates to PricingService
 * and maps between API DTOs and internal pricing types.
 */
@Component
public class InventoryPricingAdapterImpl implements InventoryPricingAdapter {

  @Autowired
  private PricingService pricingService;

  @Override
  public Optional<PricingReadDto> findById(String pricingId) {
    return pricingService.findById(pricingId).map(this::toReadDto);
  }

  @Override
  public Map<String, PricingReadDto> findByIdIn(List<String> pricingIds) {
    if (pricingIds == null || pricingIds.isEmpty()) return Map.of();
    return pricingService.findByIdIn(pricingIds).entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> toReadDto(e.getValue())));
  }

  @Override
  public String create(PricingCreateCommand command) {
    CreatePricingRequest req = new CreatePricingRequest();
    req.setShopId(command.getShopId());
    req.setMaximumRetailPrice(command.getMaximumRetailPrice());
    req.setCostPrice(command.getCostPrice());
    req.setSellingPrice(command.getSellingPrice());
    req.setRates(toRates(command.getRates()));
    req.setDefaultRate(command.getDefaultRate());
    req.setAdditionalDiscount(command.getAdditionalDiscount());
    req.setSgst(command.getSgst());
    req.setCgst(command.getCgst());
    var pricing = pricingService.createAndReturnEntity(req);
    return pricing.getId();
  }

  @Override
  public void update(String pricingId, PricingUpdateCommand command) {
    UpdatePricingRequest req = new UpdatePricingRequest();
    req.setAdditionalDiscount(command.getAdditionalDiscount());
    pricingService.update(pricingId, req);
  }

  private PricingReadDto toReadDto(Pricing p) {
    return new PricingReadDto(
        p.getMaximumRetailPrice(),
        p.getCostPrice(),
        p.getSellingPrice(),
        toRateDtos(p.getRates()),
        p.getDefaultRate(),
        p.getAdditionalDiscount(),
        p.getSgst(),
        p.getCgst());
  }

  private List<RateDto> toRateDtos(List<Rate> rates) {
    if (rates == null || rates.isEmpty()) return null;
    return rates.stream()
        .map(r -> new RateDto(r.getName(), r.getPrice()))
        .toList();
  }

  private List<Rate> toRates(List<RateDto> dtos) {
    if (dtos == null || dtos.isEmpty()) return null;
    return dtos.stream()
        .map(d -> new Rate(d.getName(), d.getPrice()))
        .toList();
  }
}
