package com.inventory.product.mapper;

import com.inventory.product.domain.model.BusinessType;
import com.inventory.product.rest.dto.request.CreateBusinessTypeRequest;
import com.inventory.product.rest.dto.response.BusinessTypeResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface BusinessTypeMapper {

  @Mapping(target = "id", source = "code")
  @Mapping(target = "enabled", constant = "true")
  @Mapping(target = "registeredAt", expression = "java(java.time.Instant.now())")
  BusinessType toEntity(CreateBusinessTypeRequest request);

  BusinessTypeResponse toResponse(BusinessType entity);
}

