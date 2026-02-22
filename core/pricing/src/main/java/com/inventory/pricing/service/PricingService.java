package com.inventory.pricing.service;

import com.inventory.common.exception.ValidationException;
import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.domain.repository.PricingRepository;
import com.inventory.pricing.rest.dto.CreatePricingRequest;
import com.inventory.pricing.rest.mapper.PricingMapper;
import com.inventory.pricing.validation.PricingValidator;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PricingService {

  @Autowired
  private PricingRepository pricingRepository;

  @Autowired
  private  PricingMapper pricingMapper;

  @Autowired
  private  PricingValidator pricingValidator;

  public Pricing createOrUpdate(CreatePricingRequest request) {

    pricingValidator.validate(request);

    // Deduplicate & normalize rates BEFORE mapping
    List<Rate> cleanedRates = deduplicateRates(request.getRates());

    Optional<Pricing> existing = Optional.empty();
    // (Since you're no longer using shopId/productId logic)

    Pricing pricing = existing
      .map(p -> {
        pricingMapper.update(p, request);
        if (cleanedRates != null) {
          p.setRates(cleanedRates);
        }
        return p;
      })
      .orElseGet(() -> {
        Pricing newPricing = pricingMapper.toEntity(request);
        newPricing.setRates(cleanedRates);
        return newPricing;
      });

    pricing.setUpdatedAt(Instant.now());

    return pricingRepository.save(pricing);
  }

  // Duplicate Rate Protection
  private List<Rate> deduplicateRates(List<Rate> rates) {

    if (rates == null || rates.isEmpty()) {
      return rates;
    }

    Map<String, Rate> uniqueRates = new LinkedHashMap<>();

    for (Rate rate : rates) {

      if (rate == null) continue;

      if (!StringUtils.hasText(rate.getName())) {
        throw new ValidationException("Rate name cannot be empty");
      }

      String normalizedName = rate.getName().trim().toLowerCase();

      if (uniqueRates.containsKey(normalizedName)) {
        throw new ValidationException(
          "Duplicate rate name: " + rate.getName()
        );
      }

      uniqueRates.put(normalizedName, rate);
    }

    return new ArrayList<>(uniqueRates.values());
  }

  public boolean exists(String pricingId) {
    return pricingRepository.existsById(pricingId);
  }

  public Pricing getById(String pricingId) {
    return pricingRepository.findById(pricingId)
      .orElseThrow(() -> new RuntimeException("Pricing not found"));
  }

  public List<Pricing> getByIdIn(List<String> ids) {
    return pricingRepository.findAllById(ids);
  }

}
