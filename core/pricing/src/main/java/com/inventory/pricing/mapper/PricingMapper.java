package com.inventory.pricing.mapper;

import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.model.PurchaseScheme;
import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.rest.dto.request.CreatePricingRequest;
import com.inventory.pricing.rest.dto.request.PricingCreateCommand;
import com.inventory.pricing.rest.dto.request.PricingUpdateCommand;
import com.inventory.pricing.rest.dto.request.UpdateDefaultPriceItem;
import com.inventory.pricing.rest.dto.request.UpdateDefaultPriceRequest;
import com.inventory.pricing.rest.dto.request.UpdatePricingRequest;
import com.inventory.pricing.rest.dto.response.PurchaseSchemeDto;
import com.inventory.pricing.rest.dto.response.PricingReadDto;
import com.inventory.pricing.rest.dto.response.PricingResponse;
import com.inventory.pricing.rest.dto.response.RateDto;
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
  @Mapping(target = "purchaseScheme", expression = "java(toPurchaseScheme(request.getPurchaseScheme()))")
  @Mapping(target = "saleScheme", expression = "java(toPurchaseScheme(request.getSaleScheme()))")
  Pricing toEntity(CreatePricingRequest request);

  @Mapping(target = "purchaseScheme", expression = "java(toPurchaseSchemeDto(pricing.getPurchaseScheme()))")
  @Mapping(target = "saleScheme", expression = "java(toPurchaseSchemeDto(pricing.getSaleScheme()))")
  PricingResponse toResponse(Pricing pricing);

  @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "shopId", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", expression = "java(java.time.Instant.now())")
  @Mapping(target = "purchaseScheme", expression = "java(toPurchaseScheme(request.getPurchaseScheme()))")
  @Mapping(target = "saleScheme", expression = "java(toPurchaseScheme(request.getSaleScheme()))")
  void updateEntity(UpdatePricingRequest request, @MappingTarget Pricing pricing);

  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "maximumRetailPrice", source = "maximumRetailPrice")
  @Mapping(target = "costPrice", source = "costPrice")
  @Mapping(target = "priceToRetail", source = "priceToRetail")
  @Mapping(target = "rates", source = "rates")
  @Mapping(target = "defaultRate", source = "defaultRate")
  @Mapping(target = "saleAdditionalDiscount", source = "saleAdditionalDiscount")
  @Mapping(target = "purchaseAdditionalDiscount", source = "purchaseAdditionalDiscount")
  @Mapping(target = "purchaseScheme", expression = "java(toPurchaseSchemeDto(command.getPurchaseScheme()))")
  @Mapping(target = "saleScheme", expression = "java(toPurchaseSchemeDto(command.getSaleScheme()))")
  @Mapping(target = "sgst", source = "sgst")
  @Mapping(target = "cgst", source = "cgst")
  CreatePricingRequest toCreatePricingRequest(PricingCreateCommand command);

  @Mapping(target = "maximumRetailPrice", source = "maximumRetailPrice")
  @Mapping(target = "costPrice", source = "costPrice")
  @Mapping(target = "priceToRetail", source = "priceToRetail")
  @Mapping(target = "rates", source = "rates")
  @Mapping(target = "defaultRate", source = "defaultRate")
  @Mapping(target = "saleAdditionalDiscount", source = "saleAdditionalDiscount")
  @Mapping(target = "purchaseAdditionalDiscount", source = "purchaseAdditionalDiscount")
  @Mapping(target = "purchaseScheme", expression = "java(toPurchaseSchemeDto(command.getPurchaseScheme()))")
  @Mapping(target = "saleScheme", expression = "java(toPurchaseSchemeDto(command.getSaleScheme()))")
  @Mapping(target = "sgst", source = "sgst")
  @Mapping(target = "cgst", source = "cgst")
  UpdatePricingRequest toUpdatePricingRequest(PricingUpdateCommand command);

  @Mapping(target = "maximumRetailPrice", source = "maximumRetailPrice")
  @Mapping(target = "costPrice", source = "costPrice")
  @Mapping(target = "priceToRetail", source = "priceToRetail")
  @Mapping(target = "rates", source = "rates")
  @Mapping(target = "defaultRate", source = "defaultRate")
  @Mapping(target = "sellingPrice", ignore = true)
  @Mapping(target = "saleAdditionalDiscount", source = "saleAdditionalDiscount")
  @Mapping(target = "purchaseAdditionalDiscount", source = "purchaseAdditionalDiscount")
  @Mapping(target = "purchaseScheme", expression = "java(toPurchaseSchemeDto(pricing.getPurchaseScheme()))")
  @Mapping(target = "saleScheme", expression = "java(toPurchaseSchemeDto(pricing.getSaleScheme()))")
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

  default PurchaseSchemeDto toPurchaseSchemeDto(PurchaseScheme s) {
    if (s == null) return null;
    PurchaseSchemeDto dto = new PurchaseSchemeDto();
    dto.setSchemeType(s.getSchemeType());
    dto.setSchemePayFor(s.getSchemePayFor());
    dto.setSchemeFree(s.getSchemeFree());
    dto.setSchemePercentage(s.getSchemePercentage());
    return dto;
  }

  default PurchaseScheme toPurchaseScheme(PurchaseSchemeDto dto) {
    if (dto == null) return null;
    return new PurchaseScheme(dto.getSchemeType(), dto.getSchemePayFor(), dto.getSchemeFree(), dto.getSchemePercentage());
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
