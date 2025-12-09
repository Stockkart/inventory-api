package com.inventory.product.rest.mapper;

import com.inventory.product.domain.model.Location;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.rest.dto.shop.LocationDto;
import com.inventory.product.rest.dto.shop.RegisterShopRequest;
import com.inventory.product.rest.dto.shop.ShopApprovalResponse;
import com.inventory.product.rest.dto.shop.ShopRegistrationResponse;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

import java.time.Instant;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ShopMapper {

  @Mapping(target = "shopId", source = "shop.shopId")
  @Mapping(target = "status", source = "shop.status")
  ShopRegistrationResponse toRegistrationResponse(Shop shop);

  @Mapping(target = "shopId", source = "shop.shopId")
  @Mapping(target = "active", source = "shop.active")
  ShopApprovalResponse toApprovalResponse(Shop shop);

  // Location mapping
  Location toLocation(LocationDto locationDto);

  @Mapping(target = "shopId", ignore = true) // Will be set in service
  @Mapping(target = "status", ignore = true)  // Will be set in mapper
  @Mapping(target = "active", ignore = true)  // Will be set in mapper
  @Mapping(target = "userLimit", ignore = true)  // Will be set in service
  @Mapping(target = "userCount", constant = "0")
  @Mapping(target = "createdAt", ignore = true)  // Will be set in mapper
  @Mapping(target = "initialAdminName", ignore = true)  // Not in new request
  @Mapping(target = "initialAdminEmail", ignore = true)  // Not in new request
  @Mapping(target = "approvedAt", ignore = true)
  Shop toEntity(RegisterShopRequest request);

  @AfterMapping
  default void setLocationAndDefaults(@MappingTarget Shop shop, RegisterShopRequest request) {
    // Set location
    if (request.getLocation() != null) {
      Location location = toLocation(request.getLocation());
      if (location.getCountry() == null || location.getCountry().trim().isEmpty()) {
        location.setCountry("IND"); // Default country
      }
      shop.setLocation(location);
    }

    // Set shop defaults: APPROVED and active = true
    shop.setStatus("APPROVED");
    shop.setActive(true);
    shop.setCreatedAt(Instant.now());
    shop.setApprovedAt(Instant.now());
  }

  // Method to update existing UserAccount with shopId
  default void updateUserAccountWithShopId(@MappingTarget UserAccount userAccount, String shopId) {
    userAccount.setShopId(shopId);
    userAccount.setRole(UserRole.OWNER); // Set role to OWNER when registering shop
    userAccount.setUpdatedAt(Instant.now());
  }
}

