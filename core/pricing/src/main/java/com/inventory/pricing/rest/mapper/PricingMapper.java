package com.inventory.pricing.rest.mapper;

import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.rest.dto.CreatePricingRequest;
import com.inventory.pricing.rest.dto.PricingDto;
import com.inventory.product.rest.dto.inventory.CreateInventoryPricingRequest;
import org.mapstruct.*;

@Mapper(
  componentModel = "spring",
  unmappedTargetPolicy = ReportingPolicy.IGNORE
)
public interface PricingMapper {

  // Product → Pricing
  @Mapping(target = "defaultPrice",
    expression = "java(org.springframework.util.StringUtils.hasText(request.getDefaultPrice()) ? request.getDefaultPrice() : \"SELLING_PRICE\")")
  CreatePricingRequest fromProductRequest(
   CreateInventoryPricingRequest request);

  // MapStruct auto-maps List<Rate> if mapping method exists
  Rate toPricingRate(com.inventory.product.domain.model.Rate rate);

  // Pricing → DTO
  PricingDto toDto(Pricing pricing);

  // Request → Entity
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "createdAt", expression = "java(java.time.Instant.now())")
  @Mapping(target = "updatedAt", expression = "java(java.time.Instant.now())")
  Pricing toEntity(CreatePricingRequest request);


  // Update existing entity
  @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
  @Mapping(target = "updatedAt", expression = "java(java.time.Instant.now())")
  void update(@MappingTarget Pricing pricing, CreatePricingRequest request);
}
