package com.inventory.pricing.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.repository.PricingRepository;
import com.inventory.pricing.rest.dto.CreatePricingRequest;
import com.inventory.pricing.rest.dto.PricingResponse;
import com.inventory.pricing.rest.dto.UpdateDefaultPriceItem;
import com.inventory.pricing.rest.dto.UpdateDefaultPriceRequest;
import com.inventory.pricing.rest.dto.UpdatePricingRequest;
import com.inventory.pricing.rest.mapper.PricingMapper;
import com.inventory.pricing.validation.PricingValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service for pricing operations.
 * Inventory stores pricingId; pricing is looked up by _id for faster retrieval.
 */
@Slf4j
@Service
@Transactional
public class PricingService {

  @Autowired
  private PricingRepository pricingRepository;

  @Autowired
  private PricingMapper pricingMapper;

  @Autowired
  private PricingValidator pricingValidator;

  /**
   * Create pricing and return the saved entity (with id).
   */
  public Pricing createAndReturnEntity(CreatePricingRequest request) {
    pricingValidator.validateCreateRequest(request);
    Pricing pricing = pricingMapper.toEntity(request);
    pricing = pricingRepository.save(pricing);
    log.debug("Created pricing with id: {}", pricing.getId());
    return pricing;
  }

  /**
   * Create pricing (legacy API returning response).
   */
  public PricingResponse create(CreatePricingRequest request) {
    return pricingMapper.toResponse(createAndReturnEntity(request));
  }

  /**
   * Bulk create pricing for multiple items.
   */
  public List<PricingResponse> createBulk(List<CreatePricingRequest> requests) {
    List<PricingResponse> created = new ArrayList<>();
    for (CreatePricingRequest request : requests) {
      try {
        created.add(create(request));
      } catch (Exception e) {
        log.error("Failed to create pricing: {}", e.getMessage());
      }
    }
    return created;
  }

  /**
   * Update default/selling price by pricing ID. Allowed fields: maximumRetailPrice, priceToRetail, rates, defaultRate.
   * Verifies pricing belongs to the given shopId.
   */
  public PricingResponse updateDefaultPrice(String pricingId, UpdateDefaultPriceRequest request, String shopId) {
    try {
      pricingValidator.validatePricingId(pricingId);
      pricingValidator.validateUpdateDefaultPriceRequest(request);

      Pricing pricing = pricingRepository.findById(pricingId)
          .orElseThrow(() -> new ResourceNotFoundException("Pricing", "id", pricingId));

      if (shopId != null && !shopId.equals(pricing.getShopId())) {
        throw new com.inventory.common.exception.AuthenticationException(
            ErrorCode.ACCESS_DENIED, "Pricing does not belong to your shop");
      }

      if (request.getMaximumRetailPrice() != null) {
        pricing.setMaximumRetailPrice(request.getMaximumRetailPrice());
      }
      if (request.getPriceToRetail() != null) {
        pricing.setPriceToRetail(request.getPriceToRetail());
      }
      if (request.getRates() != null) {
        pricing.setRates(request.getRates());
      }
      if (request.getDefaultRate() != null) {
        pricing.setDefaultRate(request.getDefaultRate());
      }
      pricing.setUpdatedAt(java.time.Instant.now());
      pricing = pricingRepository.save(pricing);

      log.debug("Updated default price for pricing: {}", pricingId);
      return pricingMapper.toResponse(pricing);

    } catch (ValidationException | ResourceNotFoundException e) {
      log.warn("Update default price failed: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while updating pricing: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error updating pricing");
    }
  }

  /**
   * Bulk update default/selling price for multiple pricing records. Verifies each pricing belongs to shopId.
   */
  public List<PricingResponse> bulkUpdateDefaultPrice(List<UpdateDefaultPriceItem> updates, String shopId) {
    if (updates == null || updates.isEmpty()) {
      return List.of();
    }
    List<PricingResponse> results = new ArrayList<>();
    for (UpdateDefaultPriceItem item : updates) {
      try {
        pricingValidator.validateUpdateDefaultPriceItem(item);
        UpdateDefaultPriceRequest req = new UpdateDefaultPriceRequest();
        req.setMaximumRetailPrice(item.getMaximumRetailPrice());
        req.setPriceToRetail(item.getPriceToRetail());
        req.setRates(item.getRates());
        req.setDefaultRate(item.getDefaultRate());
        results.add(updateDefaultPrice(item.getPricingId(), req, shopId));
      } catch (Exception e) {
        log.warn("Bulk update failed for pricingId {}: {}", item.getPricingId(), e.getMessage());
        throw e;
      }
    }
    return results;
  }

  /**
   * Update pricing by pricing ID.
   */
  public PricingResponse update(String pricingId, UpdatePricingRequest request) {
    try {
      pricingValidator.validatePricingId(pricingId);
      pricingValidator.validateUpdateRequest(request);

      Pricing pricing = pricingRepository.findById(pricingId)
          .orElseThrow(() -> new ResourceNotFoundException("Pricing", "id", pricingId));

      pricingMapper.updateEntity(request, pricing);
      pricing = pricingRepository.save(pricing);

      log.debug("Updated pricing: {}", pricingId);
      return pricingMapper.toResponse(pricing);

    } catch (ValidationException | ResourceNotFoundException e) {
      log.warn("Update pricing failed: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while updating pricing: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error updating pricing");
    }
  }

  @Transactional(readOnly = true)
  public Optional<Pricing> findById(String pricingId) {
    return pricingRepository.findById(pricingId);
  }

  /**
   * Get pricing entities by IDs. Returns map of pricingId -> Pricing.
   */
  @Transactional(readOnly = true)
  public Map<String, Pricing> findByIdIn(List<String> pricingIds) {
    if (pricingIds == null || pricingIds.isEmpty()) {
      return java.util.Collections.emptyMap();
    }
    List<String> distinctIds = pricingIds.stream().filter(id -> id != null).distinct().toList();
    if (distinctIds.isEmpty()) {
      return java.util.Collections.emptyMap();
    }
    return pricingRepository.findAllById(distinctIds).stream()
        .collect(Collectors.toMap(Pricing::getId, p -> p, (a, b) -> a));
  }
}
