package com.inventory.pricing.mapper;

import com.inventory.pricing.rest.dto.PricingCreateCommand;
import com.inventory.pricing.rest.dto.PricingReadDto;
import com.inventory.pricing.rest.dto.PricingUpdateCommand;
import com.inventory.pricing.rest.dto.RateDto;
import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.rest.dto.CreatePricingRequest;
import com.inventory.pricing.rest.dto.PricingResponse;
import com.inventory.pricing.rest.dto.UpdateDefaultPriceItem;
import com.inventory.pricing.rest.dto.UpdateDefaultPriceRequest;
import com.inventory.pricing.rest.dto.UpdatePricingRequest;
import com.inventory.pricing.utils.PricingUtils;
import com.inventory.pricing.utils.constants.PricingConstants;
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
  Pricing toEntity(CreatePricingRequest request);

  PricingResponse toResponse(Pricing pricing);

  @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "shopId", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", expression = "java(java.time.Instant.now())")
  void updateEntity(UpdatePricingRequest request, @MappingTarget Pricing pricing);

  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "maximumRetailPrice", source = "maximumRetailPrice")
  @Mapping(target = "costPrice", source = "costPrice")
  @Mapping(target = "priceToRetail", source = "priceToRetail")
  @Mapping(target = "rates", source = "rates")
  @Mapping(target = "defaultRate", source = "defaultRate")
  @Mapping(target = "additionalDiscount", source = "additionalDiscount")
  @Mapping(target = "sgst", source = "sgst")
  @Mapping(target = "cgst", source = "cgst")
  CreatePricingRequest toCreatePricingRequest(PricingCreateCommand command);

  @Mapping(target = "maximumRetailPrice", source = "maximumRetailPrice")
  @Mapping(target = "costPrice", source = "costPrice")
  @Mapping(target = "priceToRetail", source = "priceToRetail")
  @Mapping(target = "rates", source = "rates")
  @Mapping(target = "defaultRate", source = "defaultRate")
  @Mapping(target = "additionalDiscount", source = "additionalDiscount")
  @Mapping(target = "sgst", source = "sgst")
  @Mapping(target = "cgst", source = "cgst")
  UpdatePricingRequest toUpdatePricingRequest(PricingUpdateCommand command);

  @Mapping(target = "maximumRetailPrice", source = "maximumRetailPrice")
  @Mapping(target = "costPrice", source = "costPrice")
  @Mapping(target = "priceToRetail", source = "priceToRetail")
  @Mapping(target = "rates", source = "rates")
  @Mapping(target = "defaultRate", source = "defaultRate")
  @Mapping(target = "sellingPrice", ignore = true)
  @Mapping(target = "additionalDiscount", source = "additionalDiscount")
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

  RateDto toRateDto(Rate rate);
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
    return req;
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
