package com.inventory.pricing.rest.mapper;

import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.rest.dto.CreatePricingRequest;
import com.inventory.pricing.rest.dto.PricingResponse;
import com.inventory.pricing.rest.dto.UpdatePricingRequest;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;

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
}
