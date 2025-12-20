package com.inventory.user.rest.mapper;

import com.inventory.user.domain.model.Customer;
import com.inventory.user.rest.dto.customer.CustomerDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface CustomerMapper {

  @Mapping(target = "customerId", source = "id")
  CustomerDto toDto(Customer customer);
}

