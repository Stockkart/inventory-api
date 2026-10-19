package com.inventory.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.InsufficientStockException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.mapper.PurchaseMapper;
import com.inventory.product.mapper.PurchaseMapperImpl;
import com.inventory.product.rest.dto.request.AddToCartRequest;
import com.inventory.product.service.vertical.CartContributorResolver;
import com.inventory.product.validation.CheckoutValidator;
import com.inventory.user.service.CustomerService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Adding to the cart reads the request's lots once (not once per step per item) and the stock
 * held by other open quotations once (not once per item). Failures carry the same exceptions.
 *
 * <p>Each case stops at the stock check (the last item is short of stock) so the cart is never
 * priced or saved; that is enough to see every lot read the request makes before saving.
 */
class CheckoutServiceCartLotsTest {

  private static final String SHOP = "shop-1";

  private InventoryRepository inventoryRepository;
  private QuotationService quotationService;
  private CheckoutService service;
  private MockHttpServletRequest http;

  @BeforeEach
  void setUp() {
    inventoryRepository = mock(InventoryRepository.class);
    quotationService = mock(QuotationService.class);
    CartContributorResolver contributors = mock(CartContributorResolver.class);
    when(contributors.resolveContributor(anyString())).thenReturn(Optional.empty());
    CustomerService customerService = mock(CustomerService.class);
    PurchaseMapper mapper = new PurchaseMapperImpl();
    ReflectionTestUtils.setField(mapper, "customerService", customerService);
    ReflectionTestUtils.setField(mapper, "inventoryRepository", inventoryRepository);

    service = new CheckoutService();
    ReflectionTestUtils.setField(service, "inventoryRepository", inventoryRepository);
    ReflectionTestUtils.setField(service, "quotationService", quotationService);
    ReflectionTestUtils.setField(service, "cartContributorResolver", contributors);
    ReflectionTestUtils.setField(service, "customerService", customerService);
    ReflectionTestUtils.setField(service, "checkoutValidator", mock(CheckoutValidator.class));
    ReflectionTestUtils.setField(service, "packagingUnitService", mock(PackagingUnitService.class));
    ReflectionTestUtils.setField(service, "purchaseMapper", mapper);

    when(quotationService.resolveTargetCart(any(), anyString(), anyString())).thenReturn(null);
    when(quotationService.quotedBaseQuantitiesByLot(eq(SHOP), any())).thenReturn(Map.of("aaaaaaaaaaaaaaaaaaaaaaa3", 5));

    http = new MockHttpServletRequest();
    http.setAttribute("shopId", SHOP);
    http.setAttribute("userId", "user-1");
  }

  private static Inventory lot(String id, String shopId, int stock) {
    Inventory inv = new Inventory();
    inv.setId(id);
    inv.setShopId(shopId);
    inv.setName("Lot " + id);
    inv.setCurrentBaseCount(stock);
    inv.setMaximumRetailPrice(new BigDecimal("100"));
    inv.setSellingPrice(new BigDecimal("90"));
    inv.setCgst("6");
    inv.setSgst("6");
    return inv;
  }

  private static AddToCartRequest request(Object... idQty) {
    List<AddToCartRequest.CartItem> items = new ArrayList<>();
    for (int i = 0; i < idQty.length; i += 2) {
      AddToCartRequest.CartItem item = new AddToCartRequest.CartItem();
      item.setId((String) idQty[i]);
      item.setQuantity((Integer) idQty[i + 1]);
      items.add(item);
    }
    AddToCartRequest r = new AddToCartRequest();
    r.setBusinessType("medical");
    r.setCreateNewQuotation(true);
    r.setItems(items);
    return r;
  }

  @Test
  @SuppressWarnings("unchecked")
  void lotsAreReadOnceForTheRequestAndReservationsOnce() {
    // lot-3 has 6 in stock, 5 reserved elsewhere: asking for 2 fails at the stock check
    when(inventoryRepository.findAllById(anyIterable())).thenReturn(List.of(
        lot("aaaaaaaaaaaaaaaaaaaaaaa1", SHOP, 50), lot("aaaaaaaaaaaaaaaaaaaaaaa2", SHOP, 50), lot("aaaaaaaaaaaaaaaaaaaaaaa3", SHOP, 6)));

    assertThatThrownBy(() -> service.addToCart(request("aaaaaaaaaaaaaaaaaaaaaaa1", 1, "aaaaaaaaaaaaaaaaaaaaaaa2", 2, "aaaaaaaaaaaaaaaaaaaaaaa3", 2), http))
        .isInstanceOf(InsufficientStockException.class)
        .hasMessageContaining("reserved in other quotations: 5");

    ArgumentCaptor<Iterable<String>> ids = ArgumentCaptor.forClass(Iterable.class);
    verify(inventoryRepository, times(1)).findAllById(ids.capture());
    assertThat(ids.getValue()).containsExactly("aaaaaaaaaaaaaaaaaaaaaaa1", "aaaaaaaaaaaaaaaaaaaaaaa2", "aaaaaaaaaaaaaaaaaaaaaaa3");
    verify(inventoryRepository, never()).findById(anyString());
    verify(quotationService, times(1)).quotedBaseQuantitiesByLot(eq(SHOP), any());
  }

  @Test
  void sameLotTwiceIsReadOnce() {
    when(inventoryRepository.findAllById(anyIterable())).thenReturn(List.of(lot("aaaaaaaaaaaaaaaaaaaaaaa3", SHOP, 6)));

    assertThatThrownBy(() -> service.addToCart(request("aaaaaaaaaaaaaaaaaaaaaaa3", 1, "aaaaaaaaaaaaaaaaaaaaaaa3", 2), http))
        .isInstanceOf(InsufficientStockException.class);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Iterable<String>> ids = ArgumentCaptor.forClass(Iterable.class);
    verify(inventoryRepository, times(1)).findAllById(ids.capture());
    assertThat(ids.getValue()).containsExactly("aaaaaaaaaaaaaaaaaaaaaaa3");
    verify(inventoryRepository, never()).findById(anyString());
  }

  @Test
  void unknownLotStillFailsAsNotFound() {
    when(inventoryRepository.findAllById(anyIterable())).thenReturn(List.of(lot("aaaaaaaaaaaaaaaaaaaaaaa1", SHOP, 50)));
    when(inventoryRepository.findById("ccccccccccccccccccccccc1")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.addToCart(request("aaaaaaaaaaaaaaaaaaaaaaa1", 1, "ccccccccccccccccccccccc1", 1), http))
        .isInstanceOf(ResourceNotFoundException.class);
    verify(quotationService, never()).quotedBaseQuantitiesByLot(anyString(), any());
  }

  @Test
  void anotherShopsLotStillFailsTheOwnershipCheck() {
    when(inventoryRepository.findAllById(anyIterable())).thenReturn(List.of(lot("bbbbbbbbbbbbbbbbbbbbbbb1", "shop-2", 50)));

    assertThatThrownBy(() -> service.addToCart(request("bbbbbbbbbbbbbbbbbbbbbbb1", 1), http))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("does not belong to shop");
  }

  @Test
  void cartResponseReadsAllItsLotsInOneQuery() {
    com.inventory.product.domain.model.Purchase cart = new com.inventory.product.domain.model.Purchase();
    cart.setId("p-1");
    List<com.inventory.product.domain.model.PurchaseItem> lines = new ArrayList<>();
    for (String id : List.of("aaaaaaaaaaaaaaaaaaaaaaa1", "aaaaaaaaaaaaaaaaaaaaaaa2")) {
      com.inventory.product.domain.model.PurchaseItem line = new com.inventory.product.domain.model.PurchaseItem();
      com.inventory.product.util.PurchaseItemRefs.applyInventoryLine(line, id);
      line.setQuantity(BigDecimal.ONE);
      lines.add(line);
    }
    cart.setItems(lines);
    Inventory l1 = lot("aaaaaaaaaaaaaaaaaaaaaaa1", SHOP, 5);
    l1.setPurchaseAdditionalDiscount(new BigDecimal("2.5"));
    when(inventoryRepository.findAllById(anyIterable())).thenReturn(List.of(l1, lot("aaaaaaaaaaaaaaaaaaaaaaa2", SHOP, 5)));

    PurchaseMapper mapper = (PurchaseMapper) ReflectionTestUtils.getField(service, "purchaseMapper");
    var response = mapper.toAddToCartResponse(cart);

    verify(inventoryRepository, times(1)).findAllById(anyIterable());
    verify(inventoryRepository, never()).findById(anyString());
    assertThat(response.getItems().get(0).getPurchaseAdditionalDiscount()).isEqualByComparingTo("2.5");
  }
}
