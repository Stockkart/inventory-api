package com.inventory.tax.rest.mapper;

import com.inventory.tax.domain.model.GstReturn;
import com.inventory.tax.rest.dto.GstReturnDto;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

/**
 * MapStruct mapper for GST Return entity and DTO conversions.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface GstReturnMapper {
    
    GstReturnDto toDto(GstReturn entity);
    
    GstReturn toEntity(GstReturnDto dto);
}

