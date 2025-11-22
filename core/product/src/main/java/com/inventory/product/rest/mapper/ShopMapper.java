package com.inventory.product.rest.mapper;

import com.inventory.product.domain.model.Shop;
import com.inventory.product.rest.dto.shop.RegisterShopRequest;
import com.inventory.product.rest.dto.shop.ShopApprovalResponse;
import com.inventory.product.rest.dto.shop.ShopRegistrationResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ShopMapper {

  @Mapping(target = "shopId", source = "shop.shopId")
  @Mapping(target = "status", source = "shop.status")
  ShopRegistrationResponse toRegistrationResponse(Shop shop);

  @Mapping(target = "shopId", source = "shop.shopId")
  @Mapping(target = "active", source = "shop.active")
  ShopApprovalResponse toApprovalResponse(Shop shop);

  @Mapping(target = "shopId", ignore = true) // Will be set in service
  @Mapping(target = "status", ignore = true)  // Will be set in service
  @Mapping(target = "active", ignore = true)  // Will be set in service
  @Mapping(target = "userLimit", ignore = true)  // Will be set in service
  @Mapping(target = "userCount", constant = "0")  // Will be set in service
  @Mapping(target = "createdAt", ignore = true)  // Will be set in service
  @Mapping(target = "initialAdminName", source = "initialAdmin.name")
  @Mapping(target = "initialAdminEmail", source = "initialAdmin.email")
  Shop toEntity(RegisterShopRequest request);
}

