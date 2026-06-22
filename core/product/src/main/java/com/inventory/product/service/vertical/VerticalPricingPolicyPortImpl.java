package com.inventory.product.service.vertical;

import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.domain.model.Scheme;
import com.inventory.pricing.rest.dto.request.CreatePricingRequest;
import com.inventory.pricing.rest.dto.request.UpdatePricingRequest;
import com.inventory.pricing.rest.dto.response.SchemeDto;
import com.inventory.pricing.service.VerticalPricingPolicyPort;
import com.inventory.pluginengine.pricing.PricingPolicyContext;
import com.inventory.pluginengine.pricing.PricingRateEntry;
import com.inventory.pluginengine.pricing.PricingSchemeEntry;
import com.inventory.pluginengine.pricing.VerticalPricingPolicy;
import com.inventory.pricing.rest.dto.response.RateDto;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class VerticalPricingPolicyPortImpl implements VerticalPricingPolicyPort {

  private final PricingPolicyResolver pricingPolicyResolver;

  public VerticalPricingPolicyPortImpl(PricingPolicyResolver pricingPolicyResolver) {
    this.pricingPolicyResolver = pricingPolicyResolver;
  }

  @Override
  public void validateAndNormalizeCreate(CreatePricingRequest request) {
    if (request == null) {
      return;
    }
    VerticalPricingPolicy policy = pricingPolicyResolver.resolve(request.getVerticalId());
    PricingPolicyContext context = toContext(request);
    policy.validateCreate(context);
    context = policy.normalizeOnCreate(context);
    applyContext(context, request);
  }

  @Override
  public void validateAndNormalizeUpdate(
      UpdatePricingRequest request, String verticalId, Pricing existing) {
    if (request == null) {
      return;
    }
    VerticalPricingPolicy policy = pricingPolicyResolver.resolve(verticalId);
    PricingPolicyContext existingContext = toContext(existing, verticalId);
    PricingPolicyContext patchContext = toPatchContext(request, verticalId);
    policy.validateUpdate(existingContext, patchContext);
    patchContext = policy.normalizeOnUpdate(existingContext, patchContext);
    applyPatchContext(patchContext, request);
  }

  @Override
  public void normalizeEntity(Pricing pricing, String verticalId) {
    if (pricing == null) {
      return;
    }
    VerticalPricingPolicy policy = pricingPolicyResolver.resolve(verticalId);
    PricingPolicyContext context = toContext(pricing, verticalId);
    context = policy.normalizeOnCreate(context);
    applyToEntity(context, pricing);
  }

  private PricingPolicyContext toContext(CreatePricingRequest request) {
    return PricingPolicyContext.builder()
        .shopId(request.getShopId())
        .verticalId(request.getVerticalId())
        .maximumRetailPrice(request.getMaximumRetailPrice())
        .costPrice(request.getCostPrice())
        .priceToRetail(request.getPriceToRetail())
        .sellingPrice(request.getSellingPrice())
        .rates(toRateEntries(request.getRates()))
        .defaultRate(request.getDefaultRate())
        .saleAdditionalDiscount(request.getSaleAdditionalDiscount())
        .purchaseAdditionalDiscount(request.getPurchaseAdditionalDiscount())
        .purchaseScheme(toSchemeEntry(request.getPurchaseScheme()))
        .saleScheme(toSchemeEntry(request.getSaleScheme()))
        .sgst(request.getSgst())
        .cgst(request.getCgst())
        .build();
  }

  private PricingPolicyContext toContext(Pricing pricing, String verticalId) {
    return PricingPolicyContext.builder()
        .shopId(pricing.getShopId())
        .verticalId(verticalId)
        .maximumRetailPrice(pricing.getMaximumRetailPrice())
        .costPrice(pricing.getCostPrice())
        .priceToRetail(pricing.getPriceToRetail())
        .sellingPrice(pricing.getSellingPrice())
        .rates(toRateEntriesFromDomain(pricing.getRates()))
        .defaultRate(pricing.getDefaultRate())
        .saleAdditionalDiscount(pricing.getSaleAdditionalDiscount())
        .purchaseAdditionalDiscount(pricing.getPurchaseAdditionalDiscount())
        .purchaseScheme(toSchemeEntry(pricing.getPurchaseScheme()))
        .saleScheme(toSchemeEntry(pricing.getSaleScheme()))
        .sgst(pricing.getSgst())
        .cgst(pricing.getCgst())
        .build();
  }

  private PricingPolicyContext toPatchContext(UpdatePricingRequest request, String verticalId) {
    return PricingPolicyContext.builder()
        .verticalId(verticalId)
        .maximumRetailPrice(request.getMaximumRetailPrice())
        .costPrice(request.getCostPrice())
        .priceToRetail(request.getPriceToRetail())
        .sellingPrice(request.getSellingPrice())
        .rates(toRateEntries(request.getRates()))
        .defaultRate(request.getDefaultRate())
        .saleAdditionalDiscount(request.getSaleAdditionalDiscount())
        .purchaseAdditionalDiscount(request.getPurchaseAdditionalDiscount())
        .purchaseScheme(toSchemeEntry(request.getPurchaseScheme()))
        .saleScheme(toSchemeEntry(request.getSaleScheme()))
        .sgst(request.getSgst())
        .cgst(request.getCgst())
        .build();
  }

  private void applyContext(PricingPolicyContext context, CreatePricingRequest request) {
    request.setMaximumRetailPrice(context.getMaximumRetailPrice());
    request.setCostPrice(context.getCostPrice());
    request.setPriceToRetail(context.getPriceToRetail());
    request.setSellingPrice(context.getSellingPrice());
    request.setRates(toDomainRates(context.getRates()));
    request.setDefaultRate(context.getDefaultRate());
    request.setSaleAdditionalDiscount(context.getSaleAdditionalDiscount());
    request.setPurchaseAdditionalDiscount(context.getPurchaseAdditionalDiscount());
    request.setPurchaseScheme(toSchemeDto(context.getPurchaseScheme()));
    request.setSaleScheme(toSchemeDto(context.getSaleScheme()));
    request.setSgst(context.getSgst());
    request.setCgst(context.getCgst());
  }

  private void applyPatchContext(PricingPolicyContext context, UpdatePricingRequest request) {
    if (context.getSellingPrice() != null) {
      request.setSellingPrice(context.getSellingPrice());
    }
    if (context.getDefaultRate() != null) {
      request.setDefaultRate(context.getDefaultRate());
    }
  }

  private void applyToEntity(PricingPolicyContext context, Pricing pricing) {
    pricing.setMaximumRetailPrice(context.getMaximumRetailPrice());
    pricing.setCostPrice(context.getCostPrice());
    pricing.setPriceToRetail(context.getPriceToRetail());
    pricing.setSellingPrice(context.getSellingPrice());
    pricing.setRates(toDomainRates(context.getRates()));
    pricing.setDefaultRate(context.getDefaultRate());
    pricing.setSaleAdditionalDiscount(context.getSaleAdditionalDiscount());
    pricing.setPurchaseAdditionalDiscount(context.getPurchaseAdditionalDiscount());
    pricing.setPurchaseScheme(toDomainScheme(context.getPurchaseScheme()));
    pricing.setSaleScheme(toDomainScheme(context.getSaleScheme()));
  }

  private List<PricingRateEntry> toRateEntries(List<Rate> rates) {
    if (rates == null || rates.isEmpty()) {
      return null;
    }
    return rates.stream().map(r -> new PricingRateEntry(r.getName(), r.getPrice())).toList();
  }

  private List<PricingRateEntry> toRateEntriesFromDomain(List<Rate> rates) {
    return toRateEntries(rates);
  }

  private List<Rate> toDomainRates(List<PricingRateEntry> rates) {
    if (rates == null || rates.isEmpty()) {
      return null;
    }
    return rates.stream().map(r -> new Rate(r.name(), r.price())).toList();
  }

  private PricingSchemeEntry toSchemeEntry(SchemeDto dto) {
    if (dto == null) {
      return null;
    }
    return new PricingSchemeEntry(
        dto.getSchemeType(), dto.getSchemePayFor(), dto.getSchemeFree(), dto.getSchemePercentage());
  }

  private PricingSchemeEntry toSchemeEntry(Scheme scheme) {
    if (scheme == null) {
      return null;
    }
    return new PricingSchemeEntry(
        scheme.getSchemeType(),
        scheme.getSchemePayFor(),
        scheme.getSchemeFree(),
        scheme.getSchemePercentage());
  }

  private SchemeDto toSchemeDto(PricingSchemeEntry entry) {
    if (entry == null || entry.isEmpty()) {
      return null;
    }
    SchemeDto dto = new SchemeDto();
    dto.setSchemeType(entry.schemeType());
    dto.setSchemePayFor(entry.schemePayFor());
    dto.setSchemeFree(entry.schemeFree());
    dto.setSchemePercentage(entry.schemePercentage());
    return dto;
  }

  private Scheme toDomainScheme(PricingSchemeEntry entry) {
    if (entry == null || entry.isEmpty()) {
      return null;
    }
    return new Scheme(
        entry.schemeType(), entry.schemePayFor(), entry.schemeFree(), entry.schemePercentage());
  }
}
