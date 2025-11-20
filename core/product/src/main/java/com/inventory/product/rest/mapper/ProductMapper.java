package com.inventory.product.rest.mapper;

import com.inventory.product.domain.model.Product;
import com.inventory.product.rest.dto.product.ProductResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ProductMapper {

    @Mapping(target = "id", source = "barcode")
    ProductResponse toResponse(Product product);
}

