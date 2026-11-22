package com.inventory.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.user.domain.model.Customer;
import com.inventory.user.domain.repository.CustomerRepository;
import com.inventory.user.domain.repository.ShopCustomerRepository;
import com.inventory.user.mapper.CustomerMapper;
import com.inventory.user.mapper.CustomerMapperImpl;
import com.inventory.user.rest.dto.request.CreateCustomerRequest;
import com.inventory.user.utils.DrugLicence;
import com.inventory.user.validation.CustomerValidator;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The bill, quotation and estimate paths create or reuse a customer through findOrCreateCustomer,
 * so a D.L. No. given there needs its expiry too, while an older customer's unchanged D.L. keeps
 * billing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomerServiceDrugLicenceTest {

  private static final LocalDate EXPIRY = LocalDate.of(2028, 3, 31);

  @Mock private CustomerRepository customerRepository;
  @Mock private ShopCustomerRepository shopCustomerRepository;
  @Spy private CustomerMapper customerMapper = new CustomerMapperImpl();
  @Spy private CustomerValidator customerValidator = new CustomerValidator();
  @Mock private MetricsWrapper metrics;
  @InjectMocks private CustomerService customerService;

  @BeforeEach
  void setUp() {
    when(customerRepository.save(any(Customer.class))).thenAnswer(i -> i.getArgument(0));
    when(customerRepository.findByPhone(any())).thenReturn(Optional.empty());
    when(customerRepository.findByDlNo(any())).thenReturn(Optional.empty());
    when(shopCustomerRepository.existsByShopIdAndCustomerId(any(), any())).thenReturn(true);
  }

  private static CreateCustomerRequest request(String dlNo, LocalDate expiry) {
    CreateCustomerRequest r = new CreateCustomerRequest();
    r.setName("Shiv Medicals");
    r.setPhone("9876543210");
    r.setDlNo(dlNo);
    r.setDlExpiry(expiry);
    return r;
  }

  @Test
  void aNewCustomerWithADlNumberAndNoExpiryIsRefused() {
    ValidationException e =
        assertThrows(
            ValidationException.class,
            () -> customerService.findOrCreateCustomer("s1", request("20B/123", null)));
    assertEquals(DrugLicence.EXPIRY_REQUIRED, e.getMessage());
    verify(customerRepository, never()).save(any(Customer.class));
  }

  @Test
  void aNewCustomerWithItsExpiryIsSaved() {
    Customer saved = customerService.findOrCreateCustomer("s1", request("20B/123", EXPIRY));
    assertEquals(EXPIRY, saved.getDlExpiry());
  }

  @Test
  void anOlderCustomerKeepsBillingWithTheSameDlAndNoExpiry() {
    Customer old = new Customer();
    old.setId("c1");
    old.setPhone("9876543210");
    old.setDlNo("20B/123");
    when(customerRepository.findByPhone("9876543210")).thenReturn(Optional.of(old));

    Customer reused = customerService.findOrCreateCustomer("s1", request("20B/123", null));

    assertEquals("20B/123", reused.getDlNo());
    assertNull(reused.getDlExpiry());
  }

  @Test
  void aChangedDlNumberDoesNotKeepTheOldExpiry() {
    Customer old = new Customer();
    old.setId("c1");
    old.setPhone("9876543210");
    old.setDlNo("20B/123");
    old.setDlExpiry(EXPIRY);
    when(customerRepository.findByPhone("9876543210")).thenReturn(Optional.of(old));

    assertThrows(
        ValidationException.class,
        () -> customerService.findOrCreateCustomer("s1", request("20B/999", null)));
  }
}
