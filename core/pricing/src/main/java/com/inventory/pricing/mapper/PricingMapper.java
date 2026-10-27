package com.inventory.pricing.mapper;

import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.model.Scheme;
import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.rest.dto.request.CreatePricingRequest;
import com.inventory.pricing.rest.dto.request.PricingCreateCommand;
import com.inventory.pricing.rest.dto.request.PricingUpdateCommand;
import com.inventory.pricing.rest.dto.request.UpdateDefaultPriceItem;
import com.inventory.pricing.rest.dto.request.UpdateDefaultPriceRequest;
import com.inventory.pricing.rest.dto.request.UpdatePricingRequest;
import com.inventory.pricing.rest.dto.response.SchemeDto;
import com.inventory.pricing.rest.dto.response.PricingReadDto;
import com.inventory.pricing.rest.dto.response.PricingResponse;
import com.inventory.pricing.rest.dto.response.RateDto;
import com.inventory.pricing.utils.PricingUtils;
import com.inventory.pricing.utils.constants.PricingConstants;
import org.mapstruct.AfterMapping;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;
import org.springframework.util.StringUtils;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface PricingMapper {

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "createdAt", expression = "java(java.time.Instant.now())")
  @Mapping(target = "updatedAt", expression = "java(java.time.Instant.now())")
  @Mapping(target = "purchaseScheme", expression = "java(toScheme(request.getPurchaseScheme()))")
  @Mapping(target = "saleScheme", expression = "java(toScheme(request.getSaleScheme()))")
  @Mapping(target = "sellingPrice", source = "sellingPrice")
  Pricing toEntity(CreatePricingRequest request);

  @Mapping(target = "purchaseScheme", expression = "java(toSchemeDto(pricing.getPurchaseScheme()))")
  @Mapping(target = "saleScheme", expression = "java(toSchemeDto(pricing.getSaleScheme()))")
  // Mapped inline, not via toRateDtos, so an empty rate list stays [] in the response as before.
  @Mapping(target = "rates", expression = "java(pricing.getRates() == null ? null : "
      + "pricing.getRates().stream().map(this::toRateDto).toList())")
  @Mapping(target = "defaultRateOptions",
      expression = "java(com.inventory.pricing.utils.PricingUtils.defaultRateOptions(pricing.getRates()))")
  PricingResponse toResponse(Pricing pricing);

  /** A working copy, so rate generation can see a patch's new bases without touching the stored record. */
  Pricing copy(Pricing pricing);

  @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "shopId", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", expression = "java(java.time.Instant.now())")
  // Schemes are kept when absent from the request, matching every other field here. Clearing one
  // on a partial update would quietly change the landed cost that margin is measured against.
  @Mapping(target = "purchaseScheme",
      expression = "java(request.getPurchaseScheme() == null ? pricing.getPurchaseScheme() : toScheme(request.getPurchaseScheme()))")
  @Mapping(target = "saleScheme",
      expression = "java(request.getSaleScheme() == null ? pricing.getSaleScheme() : toScheme(request.getSaleScheme()))")
  @Mapping(target = "sellingPrice", source = "sellingPrice")
  void updateEntity(UpdatePricingRequest request, @MappingTarget Pricing pricing);

  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "verticalId", source = "verticalId")
  @Mapping(target = "maximumRetailPrice", source = "maximumRetailPrice")
  @Mapping(target = "costPrice", source = "costPrice")
  @Mapping(target = "priceToRetail", source = "priceToRetail")
  @Mapping(target = "sellingPrice", source = "sellingPrice")
  @Mapping(target = "rates", source = "rates")
  @Mapping(target = "defaultRate", source = "defaultRate")
  @Mapping(target = "saleAdditionalDiscount", source = "saleAdditionalDiscount")
  @Mapping(target = "purchaseAdditionalDiscount", source = "purchaseAdditionalDiscount")
  @Mapping(target = "purchaseScheme", expression = "java(toSchemeDto(command.getPurchaseScheme()))")
  @Mapping(target = "saleScheme", expression = "java(toSchemeDto(command.getSaleScheme()))")
  @Mapping(target = "sgst", source = "sgst")
  @Mapping(target = "cgst", source = "cgst")
  CreatePricingRequest toCreatePricingRequest(PricingCreateCommand command);

  @Mapping(target = "maximumRetailPrice", source = "maximumRetailPrice")
  @Mapping(target = "costPrice", source = "costPrice")
  @Mapping(target = "priceToRetail", source = "priceToRetail")
  @Mapping(target = "sellingPrice", source = "sellingPrice")
  @Mapping(target = "rates", source = "rates")
  @Mapping(target = "defaultRate", source = "defaultRate")
  @Mapping(target = "saleAdditionalDiscount", source = "saleAdditionalDiscount")
  @Mapping(target = "purchaseAdditionalDiscount", source = "purchaseAdditionalDiscount")
  @Mapping(target = "purchaseScheme", expression = "java(toSchemeDto(command.getPurchaseScheme()))")
  @Mapping(target = "saleScheme", expression = "java(toSchemeDto(command.getSaleScheme()))")
  @Mapping(target = "sgst", source = "sgst")
  @Mapping(target = "cgst", source = "cgst")
  UpdatePricingRequest toUpdatePricingRequest(PricingUpdateCommand command);

  @Mapping(target = "maximumRetailPrice", source = "maximumRetailPrice")
  @Mapping(target = "costPrice", source = "costPrice")
  @Mapping(target = "effectiveCostPrice", source = "effectiveCostPrice")
  @Mapping(target = "priceToRetail", source = "priceToRetail")
  @Mapping(target = "rates", source = "rates")
  @Mapping(target = "defaultRate", source = "defaultRate")
  @Mapping(target = "sellingPrice", ignore = true)
  @Mapping(target = "saleAdditionalDiscount", source = "saleAdditionalDiscount")
  @Mapping(target = "purchaseAdditionalDiscount", source = "purchaseAdditionalDiscount")
  @Mapping(target = "purchaseScheme", expression = "java(toSchemeDto(pricing.getPurchaseScheme()))")
  @Mapping(target = "saleScheme", expression = "java(toSchemeDto(pricing.getSaleScheme()))")
  @Mapping(target = "sgst", source = "sgst")
  @Mapping(target = "cgst", source = "cgst")
  PricingReadDto toPricingReadDto(Pricing pricing);

  default PricingReadDto toPricingReadDtoWithSellingPrice(Pricing p) {
    if (p == null) return null;
    PricingReadDto dto = toPricingReadDto(p);
    if (dto != null) {
      dto.setSellingPrice(PricingUtils.resolveEffectiveSellingPrice(p));
    }
    return dto;
  }

  @Mapping(target = "termsText", expression = "java(com.inventory.pricing.utils.RateTermsText.of(rate))")
  RateDto toRateDto(Rate rate);

  @Mapping(target = "scheme", expression = "java(toScheme(dto.getScheme()))")
  Rate toRate(RateDto dto);

  default List<RateDto> toRateDtos(List<Rate> rates) {
    if (rates == null || rates.isEmpty()) return null;
    return rates.stream().map(this::toRateDto).toList();
  }

  default List<Rate> toRates(List<RateDto> dtos) {
    if (dtos == null || dtos.isEmpty()) return null;
    return dtos.stream().map(this::toRate).toList();
  }

  default UpdateDefaultPriceRequest toUpdateDefaultPriceRequest(UpdateDefaultPriceItem item) {
    if (item == null) return null;
    UpdateDefaultPriceRequest req = new UpdateDefaultPriceRequest();
    req.setRates(item.getRates());
    req.setDefaultRate(item.getDefaultRate());
    req.setResetRates(item.getResetRates());
    return req;
  }

  default SchemeDto toSchemeDto(Scheme s) {
    if (s == null) return null;
    SchemeDto dto = new SchemeDto();
    dto.setSchemeType(s.getSchemeType());
    dto.setSchemePayFor(s.getSchemePayFor());
    dto.setSchemeFree(s.getSchemeFree());
    dto.setSchemePercentage(s.getSchemePercentage());
    return dto;
  }

  /**
   * A null field in an update means "keep it", so a field is cleared only when the request names
   * it in {@code clearFields}. Runs after {@link #updateEntity}; the caller then recomputes the
   * effective cost from what is left.
   */
  @AfterMapping
  default void applyClearFields(UpdatePricingRequest request, @MappingTarget Pricing pricing) {
    if (request == null || request.getClearFields() == null) return;
    for (String field : request.getClearFields()) {
      switch (field) {
        case PricingConstants.CLEAR_SALE_ADDITIONAL_DISCOUNT -> pricing.setSaleAdditionalDiscount(null);
        case PricingConstants.CLEAR_PURCHASE_ADDITIONAL_DISCOUNT -> pricing.setPurchaseAdditionalDiscount(null);
        case PricingConstants.CLEAR_SALE_SCHEME -> pricing.setSaleScheme(null);
        case PricingConstants.CLEAR_PURCHASE_SCHEME -> pricing.setPurchaseScheme(null);
        default -> { /* not a pricing field; the caller validated the names */ }
      }
    }
  }

  default Scheme toScheme(SchemeDto dto) {
    if (dto == null) return null;
    return new Scheme(dto.getSchemeType(), dto.getSchemePayFor(), dto.getSchemeFree(), dto.getSchemePercentage());
  }

  default void setDefaultRateAndSellingPrice(Pricing pricing) {
    if (pricing == null) return;
    if (!StringUtils.hasText(pricing.getDefaultRate())) {
      pricing.setDefaultRate(PricingConstants.DEFAULT_RATE_PRICE_TO_RETAIL);
      pricing.setSellingPrice(pricing.getPriceToRetail());
    } else {
      pricing.setSellingPrice(PricingUtils.resolveEffectiveSellingPrice(pricing));
    }
  }
}
