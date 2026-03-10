package com.inventory.user.mapper;

import com.inventory.user.domain.model.ShopVendor;
import com.inventory.user.domain.model.Vendor;
import com.inventory.user.rest.dto.request.CreateVendorRequest;
import com.inventory.user.rest.dto.request.SearchVendorRequest;
import com.inventory.user.rest.dto.response.CreateVendorResponse;
import com.inventory.user.rest.dto.response.VendorDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.springframework.util.StringUtils;

import java.time.Instant;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface VendorMapper {

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  Vendor toEntity(CreateVendorRequest request);

  @Mapping(target = "vendorId", source = "id")
  CreateVendorResponse toCreateResponse(Vendor vendor);

  @Mapping(target = "vendorId", source = "id")
  VendorDto toDto(Vendor vendor);

  default SearchVendorRequest toSearchVendorRequest(String query) {
    SearchVendorRequest r = new SearchVendorRequest();
    r.setQuery(StringUtils.hasText(query) ? query.trim() : null);
    return r;
  }

  default Vendor toVendor(String name, String contactEmail, String contactPhone,
      String address, String companyName, String businessType) {
    Vendor v = new Vendor();
    v.setName(StringUtils.hasText(name) ? name.trim() : null);
    v.setContactEmail(StringUtils.hasText(contactEmail) ? contactEmail.trim() : null);
    v.setContactPhone(StringUtils.hasText(contactPhone) ? contactPhone.trim() : null);
    v.setAddress(StringUtils.hasText(address) ? address.trim() : null);
    v.setCompanyName(StringUtils.hasText(companyName) ? companyName.trim() : null);
    v.setBusinessType(StringUtils.hasText(businessType) ? businessType.trim() : null);
    Instant now = Instant.now();
    v.setCreatedAt(now);
    v.setUpdatedAt(now);
    return v;
  }

  default void setTimestamps(com.inventory.user.domain.model.Vendor vendor) {
    if (vendor != null) {
      java.time.Instant now = java.time.Instant.now();
      vendor.setCreatedAt(now);
      vendor.setUpdatedAt(now);
    }
  }

  default ShopVendor toShopVendor(String shopId, String vendorId) {
    ShopVendor sv = new ShopVendor();
    sv.setShopId(shopId);
    sv.setVendorId(vendorId);
    sv.setCreatedAt(Instant.now());
    return sv;
  }
}
