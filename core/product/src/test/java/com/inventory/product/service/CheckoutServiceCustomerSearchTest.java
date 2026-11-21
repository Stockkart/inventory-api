package com.inventory.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.mapper.PurchaseMapper;
import com.inventory.product.validation.CheckoutValidator;
import com.inventory.user.domain.repository.CustomerRepository;
import com.inventory.user.service.CustomerService;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Searching bills by customer asks the customer service for this shop's matching customers (one
 * MongoDB query over the shop's customers) and filters the bills by those ids.
 */
class CheckoutServiceCustomerSearchTest {

  private static final String SHOP = "shop-1";

  private CustomerRepository customerRepository;
  private CustomerService customerService;
  private PurchaseRepository purchaseRepository;
  private CheckoutService service;
  private MockHttpServletRequest request;

  @BeforeEach
  void setUp() {
    customerRepository = mock(CustomerRepository.class);
    customerService = mock(CustomerService.class);
    purchaseRepository = mock(PurchaseRepository.class);
    PurchaseMapper purchaseMapper = mock(PurchaseMapper.class);
    when(purchaseMapper.toPurchaseSummaryDtos(any())).thenReturn(List.of());

    service = new CheckoutService();
    ReflectionTestUtils.setField(service, "customerRepository", customerRepository);
    ReflectionTestUtils.setField(service, "customerService", customerService);
    ReflectionTestUtils.setField(service, "purchaseRepository", purchaseRepository);
    ReflectionTestUtils.setField(service, "purchaseMapper", purchaseMapper);
    ReflectionTestUtils.setField(service, "checkoutValidator", mock(CheckoutValidator.class));

    request = new MockHttpServletRequest();
    request.setAttribute("shopId", SHOP);
    request.setAttribute("userId", "user-1");
  }

  @Test
  void onlyTheShopsOwnMatchesReachThePurchaseQuery() {
    when(customerService.findShopCustomerIdsMatchingQueryIncludingGeneral(SHOP, "Nafees"))
        .thenReturn(List.of("c1", "c3"));
    Page<com.inventory.product.domain.model.Purchase> empty = new PageImpl<>(List.of());
    when(purchaseRepository.search(any(), any(), any(), any(), any(), any())).thenReturn(empty);

    service.searchPurchases(1, 20, null, null, null, "  Nafees ", request);

    verify(customerService, times(1)).findShopCustomerIdsMatchingQueryIncludingGeneral(SHOP, "Nafees");
    verify(customerRepository, never()).searchByQuery(anyString()); // no search over every shop's customers
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<String>> searched = ArgumentCaptor.forClass(Collection.class);
    verify(purchaseRepository).search(eq(SHOP), any(), any(), any(), searched.capture(), any());
    assertThat(searched.getValue()).containsExactlyInAnyOrder("c1", "c3");
  }

  @Test
  void noMatchesMeansNoPurchaseQuery() {
    when(customerService.findShopCustomerIdsMatchingQueryIncludingGeneral(SHOP, "Nobody")).thenReturn(List.of());

    service.searchPurchases(1, 20, null, null, null, "Nobody", request);

    verify(purchaseRepository, never()).search(any(), any(), any(), any(), any(), any());
  }
}
