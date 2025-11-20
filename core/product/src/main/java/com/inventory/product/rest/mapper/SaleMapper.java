package com.inventory.product.rest.mapper;

import com.inventory.product.domain.model.Sale;
import com.inventory.product.domain.model.SaleItem;
import com.inventory.product.rest.dto.sale.CheckoutResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface SaleMapper {

    @Mapping(target = "saleId", source = "id")
    @Mapping(target = "items", expression = "java(toItemResponses(sale.getItems()))")
    CheckoutResponse toCheckoutResponse(Sale sale);

    @Mapping(target = "barcode", source = "productId")
    @Mapping(target = "price", source = "salePrice")
    CheckoutResponse.SaleItemResponse toItemResponse(SaleItem item);

    default List<CheckoutResponse.SaleItemResponse> toItemResponses(List<SaleItem> items) {
        return items == null ? java.util.List.of() :
                items.stream().map(this::toItemResponse).toList();
    }
}

