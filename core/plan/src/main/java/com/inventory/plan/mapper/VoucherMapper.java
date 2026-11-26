package com.inventory.plan.mapper;

import com.inventory.plan.domain.model.AddOnVoucher;
import com.inventory.plan.domain.model.VoucherRedemption;
import com.inventory.plan.domain.model.VoucherRejection;
import com.inventory.plan.rest.dto.request.VoucherGenerateRequest;
import com.inventory.plan.rest.dto.response.AdminVoucherResponse;
import com.inventory.plan.rest.dto.response.VoucherCheckResponse;
import com.inventory.plan.rest.dto.response.VoucherRedemptionResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface VoucherMapper {

  @Mapping(target = "code", source = "voucher.code")
  @Mapping(target = "valid", source = "valid")
  @Mapping(target = "reason", source = "reason")
  VoucherCheckResponse toCheckResponse(AddOnVoucher voucher, boolean valid, VoucherRejection reason);

  @Mapping(target = "active", expression = "java(voucher.isActive())")
  AdminVoucherResponse toAdminResponse(AddOnVoucher voucher);

  VoucherRedemptionResponse toRedemptionResponse(VoucherRedemption redemption);

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "code", ignore = true)
  @Mapping(target = "reservedCount", ignore = true)
  @Mapping(target = "redemptionCount", ignore = true)
  @Mapping(target = "active", constant = "true")
  @Mapping(target = "quantity", expression = "java(request.getQuantity() != null ? request.getQuantity() : 1)")
  AddOnVoucher toEntity(VoucherGenerateRequest request);
}
