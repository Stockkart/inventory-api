package com.inventory.pricing.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.model.SkippedRateRule;
import com.inventory.pricing.domain.repository.PricingRepository;
import com.inventory.pricing.rest.dto.request.CreatePricingRequest;
import com.inventory.pricing.rest.dto.request.UpdateDefaultPriceItem;
import com.inventory.pricing.rest.dto.request.UpdateDefaultPriceRequest;
import com.inventory.pricing.rest.dto.request.UpdatePricingRequest;
import com.inventory.pricing.rest.dto.response.PricingResponse;
import com.inventory.pricing.mapper.PricingMapper;
import com.inventory.pricing.utils.PricingUtils;
import com.inventory.pricing.validation.PricingValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

  @Autowired
  private VerticalPricingPolicyPort pricingPolicyPort;

  @Autowired
  private RateGenerationService rateGenerationService;

  /**
   * Create pricing and return the saved entity (with id).
   * Vertical policy validates fields and normalizes defaultRate/sellingPrice. Rates are generated
   * from the shop's rate rules first, so the policy sees them; a new batch needs no grant.
   */
  public Pricing createAndReturnEntity(CreatePricingRequest request) {
    pricingValidator.validateCreateBasics(request);
    generateRatesOnCreate(request);
    pricingPolicyPort.validateAndNormalizeCreate(request);
    Pricing pricing = pricingMapper.toEntity(request);
    pricingPolicyPort.normalizeEntity(pricing, request.getVerticalId());
    pricing.setEffectiveCostPrice(PricingUtils.computeEffectiveCostPrice(pricing));
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
   * Update pricing by pricing ID. Both rates and defaultRate can be updated.
   * sellingPrice is recomputed after any change. With rate rules, rule rates are regenerated and
   * a changed disc % or scheme, or a reset, needs the user's grant.
   */
  public PricingResponse updateDefaultPrice(String pricingId, UpdateDefaultPriceRequest request, String shopId,
      String userId) {
    try {
      pricingValidator.validatePricingId(pricingId);

      Pricing pricing = pricingRepository.findById(pricingId)
          .orElseThrow(() -> new ResourceNotFoundException("Pricing", "id", pricingId));

      pricingValidator.validatePricingBelongsToShop(shopId == null || shopId.equals(pricing.getShopId()));

      pricingValidator.validateUpdateDefaultPriceRequest(request, pricing.getRates(), pricing.getDefaultRate());

      if (StringUtils.hasText(request.getDefaultRate())) {
        pricing.setDefaultRate(request.getDefaultRate());
      }
      // A defaultRate counts: with rules it must pass the generator's guard, so a default rate
      // built on the selling price falls back instead of becoming the selling price.
      boolean touchesRates = request.getRates() != null || hasNames(request.getResetRates())
          || StringUtils.hasText(request.getDefaultRate());
      List<SkippedRateRule> warnings = List.of();
      if (touchesRates && rateGenerationService.hasRules(pricing.getShopId())) {
        warnings = rateGenerationService.apply(
            pricing, request.getRates(), request.getResetRates(), userId);
        logWarnings(pricingId, warnings);
      } else if (request.getRates() != null) {
        pricing.setRates(RateGenerationService.asManualRates(request.getRates()));
      }
      pricing.setSellingPrice(PricingUtils.resolveEffectiveSellingPrice(pricing));
      pricing.setUpdatedAt(java.time.Instant.now());
      pricing = pricingRepository.save(pricing);

      log.debug("Updated default price for pricing: {}", pricingId);
      return withWarnings(pricingMapper.toResponse(pricing), warnings);

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
  public List<PricingResponse> bulkUpdateDefaultPrice(List<UpdateDefaultPriceItem> updates, String shopId,
      String userId) {
    if (updates == null || updates.isEmpty()) {
      return List.of();
    }
    List<PricingResponse> results = new ArrayList<>();
    for (UpdateDefaultPriceItem item : updates) {
      try {
        Pricing pricing = pricingRepository.findById(item.getPricingId())
            .orElseThrow(() -> new ResourceNotFoundException("Pricing", "id", item.getPricingId()));
        pricingValidator.validateUpdateDefaultPriceItem(item, pricing.getRates(), pricing.getDefaultRate());
        UpdateDefaultPriceRequest req = pricingMapper.toUpdateDefaultPriceRequest(item);
        results.add(updateDefaultPrice(item.getPricingId(), req, shopId, userId));
      } catch (Exception e) {
        log.warn("Bulk update failed for pricingId {}: {}", item.getPricingId(), e.getMessage());
        throw e;
      }
    }
    return results;
  }

  /**
   * Update pricing by pricing ID. When the request touches rates or a base (PTR, MRP, selling price),
   * rates are regenerated from the shop's rate rules before the vertical policy runs.
   */
  public PricingResponse update(String pricingId, UpdatePricingRequest request, String verticalId, String userId) {
    try {
      pricingValidator.validatePricingId(pricingId);

      Pricing pricing = pricingRepository.findById(pricingId)
          .orElseThrow(() -> new ResourceNotFoundException("Pricing", "id", pricingId));

      List<SkippedRateRule> warnings = generateRatesOnUpdate(pricing, request, userId);
      pricingPolicyPort.validateAndNormalizeUpdate(request, verticalId, pricing);
      pricingValidator.validateUpdateRequest(request);

      pricingMapper.updateEntity(request, pricing);
      pricing.setEffectiveCostPrice(PricingUtils.computeEffectiveCostPrice(pricing));
      pricing = pricingRepository.save(pricing);

      log.debug("Updated pricing: {}", pricingId);
      return withWarnings(pricingMapper.toResponse(pricing), warnings);

    } catch (ValidationException | ResourceNotFoundException e) {
      log.warn("Update pricing failed: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while updating pricing: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error updating pricing");
    }
  }

  /**
   * Generates the new batch's rates into the request. A shop without rules keeps the rates sent,
   * as name and price only (see {@link RateGenerationService#asManualRates}).
   */
  private void generateRatesOnCreate(CreatePricingRequest request) {
    if (!rateGenerationService.hasRules(request.getShopId())) {
      if (request.getRates() != null) {
        request.setRates(RateGenerationService.asManualRates(request.getRates()));
      }
      return;
    }
    Pricing draft = pricingMapper.toEntity(request);
    logWarnings(null, rateGenerationService.apply(draft, request.getRates(), null, null));
    request.setRates(draft.getRates());
    request.setDefaultRate(draft.getDefaultRate());
    request.setSellingPrice(draft.getSellingPrice());
  }

  /**
   * Regenerates rates against the patched bases on a working copy, then writes the result into the
   * request, so the policy and the mapper below apply it like any other patch.
   */
  private List<SkippedRateRule> generateRatesOnUpdate(Pricing pricing, UpdatePricingRequest request,
      String userId) {
    if (request == null) {
      return List.of();
    }
    boolean touches = request.getRates() != null || hasNames(request.getResetRates())
        || request.getPriceToRetail() != null || request.getMaximumRetailPrice() != null
        || request.getSellingPrice() != null || StringUtils.hasText(request.getDefaultRate());
    if (!touches) {
      return List.of();
    }
    if (!rateGenerationService.hasRules(pricing.getShopId())) {
      if (request.getRates() != null) {
        request.setRates(RateGenerationService.asManualRates(request.getRates()));
      }
      return List.of();
    }
    Pricing draft = pricingMapper.copy(pricing);
    draft.setRates(null);
    pricingMapper.updateEntity(request, draft);
    draft.setRates(pricing.getRates() == null ? null : new ArrayList<>(pricing.getRates()));
    List<SkippedRateRule> warnings = rateGenerationService.apply(
        draft, request.getRates(), request.getResetRates(), userId);
    logWarnings(pricing.getId(), warnings);
    request.setRates(draft.getRates() == null ? new ArrayList<>() : draft.getRates());
    request.setDefaultRate(draft.getDefaultRate());
    request.setSellingPrice(draft.getSellingPrice());
    return warnings;
  }

  /** Puts the write's below-cost warnings on the response; null when there are none. */
  private static PricingResponse withWarnings(PricingResponse response, List<SkippedRateRule> warnings) {
    if (response != null) {
      response.setWarnings(warnings == null || warnings.isEmpty() ? null : List.copyOf(warnings));
    }
    return response;
  }

  private static boolean hasNames(List<String> names) {
    return names != null && !names.isEmpty();
  }

  private static void logWarnings(String pricingId, List<SkippedRateRule> warnings) {
    for (SkippedRateRule w : warnings) {
      log.info("Rate '{}' on pricing {}: {}", w.name(), pricingId, w.reason());
    }
  }

  @Transactional(readOnly = true)
  public Optional<Pricing> findById(String pricingId) {
    return pricingRepository.findById(pricingId);
  }

  /**
   * Get pricing by ID. Verifies pricing belongs to the given shopId.
   */
  @Transactional(readOnly = true)
  public PricingResponse getById(String pricingId, String shopId) {
    pricingValidator.validatePricingId(pricingId);
    Pricing pricing = pricingRepository.findById(pricingId)
        .orElseThrow(() -> new ResourceNotFoundException("Pricing", "id", pricingId));
    pricingValidator.validatePricingBelongsToShop(shopId == null || shopId.equals(pricing.getShopId()));
    return pricingMapper.toResponse(pricing);
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
