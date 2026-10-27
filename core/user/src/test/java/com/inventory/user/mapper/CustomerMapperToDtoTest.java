package com.inventory.user.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.inventory.common.gst.PostalAddress;
import com.inventory.user.domain.model.Customer;
import com.inventory.user.rest.dto.request.UpdateCustomerRequest;
import com.inventory.user.rest.dto.response.CustomerDto;
import org.junit.jupiter.api.Test;

/**
 * The read DTO hands the customer back exactly as stored. The id goes straight into
 * {@code PATCH /customers/{id}}, so an uppercased id finds no shop link and every edit fails.
 */
class CustomerMapperToDtoTest {

  private final CustomerMapper mapper = new CustomerMapperImpl();

  @Test
  void idNameAndContactComeBackAsStored() {
    Customer customer = new Customer();
    customer.setId("6adc0b2ec2db5911941a6074");
    customer.setName("bhalla sports");
    customer.setPhone("98765434501");
    customer.setEmail("owner@bhalla.in");
    customer.setAddress("delha");
    customer.setDlNo("dl-20b/123");
    customer.setPan("aabcb5502g");
    customer.setGstinStatus("Active");
    customer.setUserId("6a2c44ada0b2c94b9183240f");
    customer.setPostalAddress(new PostalAddress("delha", null, "gaya", null, "10", "823001"));

    CustomerDto dto = mapper.toDto(customer);

    assertEquals("6adc0b2ec2db5911941a6074", dto.getCustomerId());
    assertEquals("bhalla sports", dto.getName());
    assertEquals("98765434501", dto.getPhone());
    assertEquals("owner@bhalla.in", dto.getEmail());
    assertEquals("delha", dto.getAddress());
    assertEquals("dl-20b/123", dto.getDlNo());
    assertEquals("aabcb5502g", dto.getPan());
    assertEquals("Active", dto.getGstinStatus());
    assertEquals("6a2c44ada0b2c94b9183240f", dto.getUserId());
    assertEquals("delha", dto.getPostalAddress().getLine1());
  }

  @Test
  void aSavedGstinIsStillStoredUppercase() {
    Customer customer = new Customer();
    UpdateCustomerRequest request = new UpdateCustomerRequest();
    request.setGstin(" 09aabcb5502g1z1 ");

    mapper.applyUpdate(request, customer);

    assertEquals("09AABCB5502G1Z1", customer.getGstin());
    assertEquals("09AABCB5502G1Z1", mapper.toDto(customer).getGstin());
  }
}
