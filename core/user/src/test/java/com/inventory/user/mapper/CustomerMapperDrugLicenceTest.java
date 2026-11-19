package com.inventory.user.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.user.domain.model.Customer;
import com.inventory.user.rest.dto.request.CreateCustomerRequest;
import com.inventory.user.rest.dto.request.UpdateCustomerRequest;
import com.inventory.user.utils.DrugLicence;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CustomerMapperDrugLicenceTest {

  private final CustomerMapper mapper = new CustomerMapperImpl();

  @Test
  void createStoresTheExpiryWithTheNumber() {
    CreateCustomerRequest request = new CreateCustomerRequest();
    request.setDlNo(" 20B/123 ");
    request.setDlExpiry(LocalDate.of(2028, 3, 31));

    Customer customer = mapper.toCustomer(request);

    assertEquals("20B/123", customer.getDlNo());
    assertEquals(LocalDate.of(2028, 3, 31), customer.getDlExpiry());
  }

  @Test
  void createDropsAnExpiryWithoutANumber() {
    CreateCustomerRequest request = new CreateCustomerRequest();
    request.setDlExpiry(LocalDate.of(2028, 3, 31));

    assertNull(mapper.toCustomer(request).getDlExpiry());
  }

  @Test
  void clearingTheNumberClearsTheExpiry() {
    Customer customer = new Customer();
    customer.setDlNo("20B/123");
    customer.setDlExpiry(LocalDate.of(2028, 3, 31));
    UpdateCustomerRequest request = new UpdateCustomerRequest();
    request.setDlNo("");

    mapper.applyUpdate(request, customer);

    assertNull(customer.getDlNo());
    assertNull(customer.getDlExpiry());
  }

  @Test
  void anUpdateCanChangeOnlyTheExpiry() {
    Customer customer = new Customer();
    customer.setDlNo("20B/123");
    customer.setDlExpiry(LocalDate.of(2026, 3, 31));
    UpdateCustomerRequest request = new UpdateCustomerRequest();
    request.setDlExpiry(LocalDate.of(2031, 3, 31));

    mapper.applyUpdate(request, customer);

    assertEquals("20B/123", customer.getDlNo());
    assertEquals(LocalDate.of(2031, 3, 31), customer.getDlExpiry());
  }

  @Test
  void theDtoSaysWhetherTheLicenceHasExpired() {
    Customer lapsed = new Customer();
    lapsed.setDlNo("20B/123");
    lapsed.setDlExpiry(DrugLicence.today().minusDays(1));
    Customer valid = new Customer();
    valid.setDlNo("20B/124");
    valid.setDlExpiry(DrugLicence.today());

    assertTrue(mapper.toDto(lapsed).getDlExpired());
    assertFalse(mapper.toDto(valid).getDlExpired());
    assertNull(mapper.toDto(new Customer()).getDlExpired());
  }

  @Test
  void theModelSaysWhetherTheLicenceHasExpired() {
    Customer c = new Customer();
    assertNull(c.dlExpiredToday());
    c.setDlExpiry(DrugLicence.today());
    assertFalse(c.dlExpiredToday());
    c.setDlExpiry(DrugLicence.today().minusDays(1));
    assertTrue(c.dlExpiredToday());
  }
}
