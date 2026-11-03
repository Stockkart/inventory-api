package com.inventory.user.mapper;

import com.inventory.common.gst.Gstin;
import com.inventory.common.gst.PostalAddress;
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
import org.mapstruct.Named;
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
    c.setPostalAddress(cleanAddress(request.getPostalAddress()));
    c.setEmail(TextUtils.trimToNull(request.getEmail()));
    c.setGstin(cleanGstin(request.getGstin()));
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
    if (request.getPostalAddress() != null) {
      customer.setPostalAddress(cleanAddress(request.getPostalAddress()));
    }
    if (request.getGstin() != null) {
      customer.setGstin(cleanGstin(request.getGstin()));
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
    if (cleanAddress(request.getPostalAddress()) != null) {
      customer.setPostalAddress(cleanAddress(request.getPostalAddress()));
    }
    if (cleanGstin(request.getGstin()) != null) {
      customer.setGstin(cleanGstin(request.getGstin()));
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

  /**
   * Upper-case, no spaces, or null when blank — the shape the registry and indexes expect.
   *
   * <p>{@code @Named} keeps MapStruct from using this as the String-to-String conversion for
   * every field of {@link #toDto}; without it the customer id, name and email come back
   * uppercased and the id no longer matches the stored one.
   */
  @Named("cleanGstin")
  static String cleanGstin(String raw) {
    String g = Gstin.normalize(raw);
    return g == null || g.isEmpty() ? null : g;
  }

  /** Null when every field is blank, so an empty form does not store an empty object. */
  @Named("cleanAddress")
  static PostalAddress cleanAddress(PostalAddress address) {
    if (address == null) {
      return null;
    }
    PostalAddress a =
        new PostalAddress(
            TextUtils.trimToNull(address.getLine1()),
            TextUtils.trimToNull(address.getLine2()),
            TextUtils.trimToNull(address.getCity()),
            TextUtils.trimToNull(address.getDistrict()),
            TextUtils.trimToNull(address.getStateCode()),
            TextUtils.trimToNull(address.getPincode()));
    boolean empty =
        a.getLine1() == null
            && a.getLine2() == null
            && a.getCity() == null
            && a.getDistrict() == null
            && a.getStateCode() == null
            && a.getPincode() == null;
    return empty ? null : a;
  }

  default ShopCustomer toShopCustomer(String shopId, String customerId) {
    ShopCustomer sc = new ShopCustomer();
    sc.setShopId(shopId);
    sc.setCustomerId(customerId);
    sc.setCreatedAt(Instant.now());
    return sc;
  }
}
