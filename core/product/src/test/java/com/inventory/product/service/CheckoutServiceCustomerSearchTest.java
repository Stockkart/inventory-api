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
import com.inventory.user.domain.model.Customer;
import com.inventory.user.domain.model.ShopCustomer;
import com.inventory.user.domain.repository.CustomerRepository;
import com.inventory.user.domain.repository.ShopCustomerRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

/** Searching bills by customer checks the shop's membership for all candidates in one query. */
class CheckoutServiceCustomerSearchTest {

  private static final String SHOP = "shop-1";

  private CustomerRepository customerRepository;
  private ShopCustomerRepository shopCustomerRepository;
  private PurchaseRepository purchaseRepository;
  private CheckoutService service;
  private MockHttpServletRequest request;

  @BeforeEach
  void setUp() {
    customerRepository = mock(CustomerRepository.class);
    shopCustomerRepository = mock(ShopCustomerRepository.class);
    purchaseRepository = mock(PurchaseRepository.class);
    PurchaseMapper purchaseMapper = mock(PurchaseMapper.class);
    when(purchaseMapper.toPurchaseSummaryDtos(any())).thenReturn(List.of());

    service = new CheckoutService();
    ReflectionTestUtils.setField(service, "customerRepository", customerRepository);
    ReflectionTestUtils.setField(service, "shopCustomerRepository", shopCustomerRepository);
    ReflectionTestUtils.setField(service, "purchaseRepository", purchaseRepository);
    ReflectionTestUtils.setField(service, "purchaseMapper", purchaseMapper);
    ReflectionTestUtils.setField(service, "checkoutValidator", mock(CheckoutValidator.class));

    request = new MockHttpServletRequest();
    request.setAttribute("shopId", SHOP);
    request.setAttribute("userId", "user-1");
  }

  private static Customer customer(String id) {
    Customer c = new Customer();
    c.setId(id);
    return c;
  }

  @Test
  void onlyTheShopsOwnCandidatesReachThePurchaseQueryFromOneMembershipLookup() {
    when(customerRepository.searchByQuery("Nafees"))
        .thenReturn(List.of(customer("c1"), customer("c2"), customer("c3")));
    // c2 belongs to another shop
    when(shopCustomerRepository.findByShopIdAndCustomerIdIn(eq(SHOP), anyCollection()))
        .thenReturn(List.of(
            new ShopCustomer("l1", SHOP, "c1", null),
            new ShopCustomer("l3", SHOP, "c3", null)));
    Page<com.inventory.product.domain.model.Purchase> empty = new PageImpl<>(List.of());
    when(purchaseRepository.search(any(), any(), any(), any(), any(), any())).thenReturn(empty);

    service.searchPurchases(1, 20, null, null, null, "  Nafees ", request);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<String>> asked = ArgumentCaptor.forClass(Collection.class);
    verify(shopCustomerRepository, times(1)).findByShopIdAndCustomerIdIn(eq(SHOP), asked.capture());
    assertThat(asked.getValue()).containsExactlyInAnyOrder("c1", "c2", "c3");
    verify(shopCustomerRepository, never()).existsByShopIdAndCustomerId(anyString(), anyString());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<String>> searched = ArgumentCaptor.forClass(Collection.class);
    verify(purchaseRepository).search(eq(SHOP), any(), any(), any(), searched.capture(), any());
    assertThat(new ArrayList<>(searched.getValue())).containsExactlyInAnyOrder("c1", "c3");
  }

  @Test
  void noCandidatesMeansNoMembershipOrPurchaseQuery() {
    when(customerRepository.searchByQuery("Nobody")).thenReturn(List.of());

    service.searchPurchases(1, 20, null, null, null, "Nobody", request);

    verify(shopCustomerRepository, never()).findByShopIdAndCustomerIdIn(anyString(), anyCollection());
    verify(purchaseRepository, never()).search(any(), any(), any(), any(), any(), any());
  }
}
