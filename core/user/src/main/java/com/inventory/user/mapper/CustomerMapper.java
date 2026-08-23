package com.inventory.user.mapper;

import com.inventory.user.domain.model.Customer;
import com.inventory.user.domain.model.ShopCustomer;
import com.inventory.user.domain.model.enums.CustomerPartyType;
import com.inventory.user.rest.dto.request.CreateCustomerRequest;
import com.inventory.user.rest.dto.request.UpdateCustomerRequest;
import com.inventory.user.rest.dto.response.CustomerDto;
import com.inventory.user.utils.TextUtils;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

import java.time.Instant;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface CustomerMapper {

  @Mapping(target = "customerId", source = "id")
  @Mapping(target = "panNo", ignore = true)
  CustomerDto toDto(Customer customer);

  @AfterMapping
  default void setPanNoFromGstin(@MappingTarget CustomerDto dto, Customer customer) {
    String gstin = customer.getGstin();
    if (gstin != null && gstin.length() >= 12) {
      dto.setPanNo(gstin.substring(2, 12));
    }
    if (dto.getPartyType() == null) {
      dto.setPartyType(CustomerPartyType.CONSUMER);
    }
    if (dto.getIsGeneral() == null) {
      dto.setIsGeneral(Boolean.FALSE);
    }
  }

  default Customer toCustomer(CreateCustomerRequest request) {
    if (request == null) {
      return null;
    }
    Customer c = new Customer();
    c.setName(TextUtils.trimToNull(request.getName()));
    c.setPhone(TextUtils.trimToNull(request.getPhone()));
    c.setAddress(TextUtils.trimToNull(request.getAddress()));
    c.setEmail(TextUtils.trimToNull(request.getEmail()));
    c.setGstin(TextUtils.trimToNull(request.getGstin()));
    c.setDlNo(TextUtils.trimToNull(request.getDlNo()));
    c.setPan(TextUtils.trimToNull(request.getPan()));
    c.setPartyType(
        request.getPartyType() != null ? request.getPartyType() : CustomerPartyType.CONSUMER);
    c.setIsGeneral(false);
    Instant now = Instant.now();
    c.setCreatedAt(now);
    c.setUpdatedAt(now);
    return c;
  }

  default void applyUpdate(UpdateCustomerRequest request, @MappingTarget Customer customer) {
    if (request == null) {
      return;
    }
    if (request.getName() != null) {
      customer.setName(TextUtils.trimToNull(request.getName()));
    }
    if (request.getPhone() != null) {
      customer.setPhone(TextUtils.trimToNull(request.getPhone()));
    }
    if (request.getEmail() != null) {
      customer.setEmail(TextUtils.trimToNull(request.getEmail()));
    }
    if (request.getAddress() != null) {
      customer.setAddress(TextUtils.trimToNull(request.getAddress()));
    }
    if (request.getGstin() != null) {
      customer.setGstin(TextUtils.trimToNull(request.getGstin()));
    }
    if (request.getDlNo() != null) {
      customer.setDlNo(TextUtils.trimToNull(request.getDlNo()));
    }
    if (request.getPan() != null) {
      customer.setPan(TextUtils.trimToNull(request.getPan()));
    }
    if (request.getPartyType() != null) {
      customer.setPartyType(request.getPartyType());
    }
    customer.setUpdatedAt(Instant.now());
  }

  /** Apply create request fields to an existing customer (e.g. when reusing by unique key). */
  default void applyCreateRequest(CreateCustomerRequest request, @MappingTarget Customer customer) {
    if (request == null || customer.isGeneralCustomer()) {
      return;
    }
    if (TextUtils.trimToNull(request.getName()) != null) {
      customer.setName(TextUtils.trimToNull(request.getName()));
    }
    if (TextUtils.trimToNull(request.getPhone()) != null) {
      customer.setPhone(TextUtils.trimToNull(request.getPhone()));
    }
    if (TextUtils.trimToNull(request.getEmail()) != null) {
      customer.setEmail(TextUtils.trimToNull(request.getEmail()));
    }
    if (TextUtils.trimToNull(request.getAddress()) != null) {
      customer.setAddress(TextUtils.trimToNull(request.getAddress()));
    }
    if (TextUtils.trimToNull(request.getGstin()) != null) {
      customer.setGstin(TextUtils.trimToNull(request.getGstin()));
    }
    if (TextUtils.trimToNull(request.getDlNo()) != null) {
      customer.setDlNo(TextUtils.trimToNull(request.getDlNo()));
    }
    if (TextUtils.trimToNull(request.getPan()) != null) {
      customer.setPan(TextUtils.trimToNull(request.getPan()));
    }
    if (request.getPartyType() != null) {
      customer.setPartyType(request.getPartyType());
    }
    customer.setUpdatedAt(Instant.now());
  }

  default ShopCustomer toShopCustomer(String shopId, String customerId) {
    ShopCustomer sc = new ShopCustomer();
    sc.setShopId(shopId);
    sc.setCustomerId(customerId);
    sc.setCreatedAt(Instant.now());
    return sc;
  }
}
