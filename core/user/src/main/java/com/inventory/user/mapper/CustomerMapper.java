package com.inventory.user.mapper;

import com.inventory.user.domain.model.Customer;
import com.inventory.user.domain.model.ShopCustomer;
import com.inventory.user.rest.dto.response.CustomerDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.springframework.util.StringUtils;

import java.time.Instant;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface CustomerMapper {

  @Mapping(target = "customerId", source = "id")
  CustomerDto toDto(Customer customer);

  default Customer toCustomer(String name, String phone, String address, String email,
      String gstin, String dlNo, String pan, String userId) {
    Customer c = new Customer();
    c.setName(StringUtils.hasText(name) ? name.trim() : null);
    c.setPhone(StringUtils.hasText(phone) ? phone.trim() : null);
    c.setAddress(StringUtils.hasText(address) ? address.trim() : null);
    c.setEmail(StringUtils.hasText(email) ? email.trim() : null);
    c.setGstin(StringUtils.hasText(gstin) ? gstin.trim() : null);
    c.setDlNo(StringUtils.hasText(dlNo) ? dlNo.trim() : null);
    c.setPan(StringUtils.hasText(pan) ? pan.trim() : null);
    c.setUserId(StringUtils.hasText(userId) ? userId.trim() : null);
    Instant now = Instant.now();
    c.setCreatedAt(now);
    c.setUpdatedAt(now);
    return c;
  }

  default ShopCustomer toShopCustomer(String shopId, String customerId) {
    ShopCustomer sc = new ShopCustomer();
    sc.setShopId(shopId);
    sc.setCustomerId(customerId);
    sc.setCreatedAt(Instant.now());
    return sc;
  }
}
