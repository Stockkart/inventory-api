package com.inventory.product.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.product.domain.model.Purchase;
import com.inventory.product.rest.dto.response.PurchaseSummaryDto;
import com.inventory.user.domain.model.Customer;
import com.inventory.user.service.CustomerService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** A page of purchases resolves its customers in one query, with the same display rules as one at a time. */
class PurchaseSummaryBatchMappingTest {

  private CustomerService customerService;
  private PurchaseMapper mapper;

  @BeforeEach
  void setUp() {
    customerService = mock(CustomerService.class);
    mapper = new PurchaseMapperImpl();
    ReflectionTestUtils.setField(mapper, "customerService", customerService);
  }

  private static Purchase purchase(String id, String customerId, String counterLabel) {
    Purchase p = new Purchase();
    p.setId(id);
    p.setCustomerId(customerId);
    p.setCustomerName(counterLabel);
    return p;
  }

  private static Customer customer(String id, String name, String phone, boolean general) {
    Customer c = new Customer();
    c.setId(id);
    c.setName(name);
    c.setPhone(phone);
    c.setIsGeneral(general);
    return c;
  }

  @Test
  void pageLooksCustomersUpOnceAndKeepsTheDisplayRules() {
    when(customerService.getCustomersByIds(anyCollection())).thenReturn(Map.of(
        "c1", customer("c1", "Ravi Traders", "9000000001", false),
        "g", customer("g", "General Customer", null, true)));

    List<PurchaseSummaryDto> out = mapper.toPurchaseSummaryDtos(List.of(
        purchase("p1", "c1", null),             // linked customer: its name and phone
        purchase("p2", "c1", "Ravi (counter)"), // counter label wins the name; phone still the customer's
        purchase("p3", "g", "Walk-in 7"),       // walk-in: the counter label, never "General Customer"
        purchase("p4", "missing", "Asha"),      // customer gone: the counter label
        purchase("p5", null, "9876543210")));   // no customer, phone-like label: shown as the phone

    assertThat(out).extracting(PurchaseSummaryDto::getCustomerName)
        .containsExactly("Ravi Traders", "Ravi (counter)", "Walk-in 7", "Asha", null);
    assertThat(out).extracting(PurchaseSummaryDto::getCustomerPhone)
        .containsExactly("9000000001", "9000000001", null, null, "9876543210");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<String>> ids = ArgumentCaptor.forClass(Collection.class);
    verify(customerService, times(1)).getCustomersByIds(ids.capture());
    assertThat(new ArrayList<>(ids.getValue())).contains("c1", "g", "missing");
    verify(customerService, never()).getCustomerById(any());
  }

  @Test
  void emptyPageMakesNoLookup() {
    assertThat(mapper.toPurchaseSummaryDtos(List.of())).isEmpty();
    verify(customerService, never()).getCustomersByIds(anyCollection());
  }

  @Test
  void singlePurchaseStillLooksUpItsOwnCustomer() {
    when(customerService.getCustomerById("c1")).thenReturn(Optional.of(customer("c1", "Ravi", "1", false)));

    PurchaseSummaryDto dto = mapper.toPurchaseSummaryDto(purchase("p1", "c1", null));

    assertThat(dto.getCustomerName()).isEqualTo("Ravi");
    verify(customerService, never()).getCustomersByIds(anyCollection());
  }
}
