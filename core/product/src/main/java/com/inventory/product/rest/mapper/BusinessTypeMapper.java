package com.inventory.product.rest.mapper;

import com.inventory.product.domain.model.BusinessType;
import com.inventory.product.rest.dto.business.BusinessTypeResponse;
import com.inventory.product.rest.dto.business.CreateBusinessTypeRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface BusinessTypeMapper {

    @Mapping(target = "id", source = "code")
    @Mapping(target = "enabled", constant = "true")
    @Mapping(target = "registeredAt", expression = "java(java.time.Instant.now())")
    BusinessType toEntity(CreateBusinessTypeRequest request);

    BusinessTypeResponse toResponse(BusinessType entity);
}

