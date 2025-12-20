package com.inventory.user.rest.mapper;

import com.inventory.user.domain.model.Vendor;
import com.inventory.user.rest.dto.vendor.CreateVendorRequest;
import com.inventory.user.rest.dto.vendor.CreateVendorResponse;
import com.inventory.user.rest.dto.vendor.VendorDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

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
}

