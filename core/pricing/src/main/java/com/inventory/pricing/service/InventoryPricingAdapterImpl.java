package com.inventory.pricing.service;

import com.inventory.pricing.mapper.PricingMapper;
import com.inventory.pricing.rest.dto.request.CreatePricingRequest;
import com.inventory.pricing.rest.dto.request.PricingCreateCommand;
import com.inventory.pricing.rest.dto.request.PricingUpdateCommand;
import com.inventory.pricing.rest.dto.request.UpdatePricingRequest;
import com.inventory.pricing.rest.dto.response.PricingReadDto;
import com.inventory.pricing.validation.PricingValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Adapter implementing InventoryPricingAdapter. Delegates to PricingService
 * and maps between DTOs and internal pricing types.
 */
@Component
public class InventoryPricingAdapterImpl implements InventoryPricingAdapter {

  @Autowired
  private PricingService pricingService;

  @Autowired
  private PricingMapper pricingMapper;

  @Autowired
  private PricingValidator pricingValidator;

  @Override
  public Optional<PricingReadDto> findById(String pricingId) {
    return pricingService.findById(pricingId).map(pricingMapper::toPricingReadDtoWithSellingPrice);
  }

  @Override
  public Map<String, PricingReadDto> findByIdIn(List<String> pricingIds) {
    if (pricingIds == null || pricingIds.isEmpty()) return Map.of();
    return pricingService.findByIdIn(pricingIds).entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> pricingMapper.toPricingReadDtoWithSellingPrice(e.getValue())));
  }

  @Override
  public String create(PricingCreateCommand command) {
    pricingValidator.validateCreateCommand(command);
    CreatePricingRequest req = pricingMapper.toCreatePricingRequest(command);
    var pricing = pricingService.createAndReturnEntity(req);
    return pricing.getId();
  }

  @Override
  public void update(String pricingId, PricingUpdateCommand command) {
    pricingValidator.validateUpdateCommand(command);
    UpdatePricingRequest req = pricingMapper.toUpdatePricingRequest(command);
    pricingService.update(pricingId, req);
  }
}
