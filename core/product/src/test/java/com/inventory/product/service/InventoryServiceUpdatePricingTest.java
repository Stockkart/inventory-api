package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.inventory.pricing.rest.dto.response.PricingReadDto;
import com.inventory.pricing.service.InventoryPricingAdapter;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.catalog.InventoryProductReadHandler;
import com.inventory.product.domain.model.pricing.InventoryPricingReadHandler;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.mapper.InventoryMapper;
import com.inventory.product.rest.dto.request.UpdateInventoryRequest;
import com.inventory.product.rest.dto.response.InventoryDetailResponse;
import com.inventory.product.service.vertical.InventoryVerticalExtensionHandler;
import com.inventory.product.service.vertical.InventoryVerticalValidationHandler;
import com.inventory.product.validation.InventoryValidator;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The product-search Edit dialog shows whatever PUT /inventory/{id} returns. Prices live in the
 * pricing record, which the write aspect updates while the lot is saved, so the response has to
 * carry the pricing as stored after the update, not as it was when the lot was loaded.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryServiceUpdatePricingTest {

  @Mock private InventoryRepository inventoryRepository;
  @Mock private InventoryValidator inventoryValidator;
  @Mock private ProductService productService;
  @Mock private InventoryVerticalValidationHandler inventoryVerticalValidationHandler;
  @Mock private InventoryVerticalExtensionHandler inventoryVerticalExtensionHandler;
  @Mock private InventoryProductReadHandler inventoryProductReadHandler;
  @Mock private InventoryPricingAdapter pricingPort;
  @Spy private InventoryMapper inventoryMapper = Mappers.getMapper(InventoryMapper.class);
  @InjectMocks private InventoryService inventoryService;

  /** Pricing record as the write aspect leaves it after the update. */
  private final PricingReadDto stored = new PricingReadDto();

  @BeforeEach
  void setUp() {
    Inventory lot = new Inventory();
    lot.setId("lot-1");
    lot.setShopId("s1");
    lot.setProductId("p1");
    lot.setPricingId("pr-1");
    // Pricing as hydrated when the lot was loaded, before the update.
    lot.setPriceToRetail(new BigDecimal("67.8"));
    lot.setMaximumRetailPrice(new BigDecimal("89"));
    lot.setCostPrice(new BigDecimal("67.8"));
    lot.setSgst("2.5");
    lot.setCgst("2.5");

    when(inventoryRepository.findById("lot-1")).thenReturn(Optional.of(lot));
    when(inventoryRepository.save(any(Inventory.class))).thenAnswer(inv -> inv.getArgument(0));
    when(productService.resolveForRegistration(anyString(), any(), eq("s1"))).thenReturn("p1");
    when(productService.findInShop(eq("s1"), anyString())).thenReturn(Optional.empty());
    when(inventoryVerticalExtensionHandler.mergeDetail(eq("s1"), any(), any()))
        .thenAnswer(inv -> inv.getArgument(2));

    stored.setPriceToRetail(new BigDecimal("77.5"));
    stored.setMaximumRetailPrice(new BigDecimal("99.5"));
    stored.setCostPrice(new BigDecimal("55.25"));
    stored.setSgst("6");
    stored.setCgst("6");
    stored.setSaleAdditionalDiscount(new BigDecimal("3"));
    when(pricingPort.findById("pr-1")).thenReturn(Optional.of(stored));

    InventoryPricingReadHandler pricingReadHandler = new InventoryPricingReadHandler();
    ReflectionTestUtils.setField(pricingReadHandler, "pricingPort", pricingPort);
    ReflectionTestUtils.setField(
        inventoryService, "inventoryPricingReadHandler", pricingReadHandler);
  }

  @Test
  void theResponseCarriesThePricesAsStoredAfterTheUpdate() {
    UpdateInventoryRequest request = new UpdateInventoryRequest();
    request.setPriceToRetail(new BigDecimal("77.5"));
    request.setMaximumRetailPrice(new BigDecimal("99.5"));
    request.setCostPrice(new BigDecimal("55.25"));
    request.setSgst("6");
    request.setCgst("6");
    request.setSaleAdditionalDiscount(new BigDecimal("3"));

    InventoryDetailResponse response = inventoryService.update("lot-1", request, "s1");

    assertEquals(0, new BigDecimal("77.5").compareTo(response.getPriceToRetail()));
    assertEquals(0, new BigDecimal("99.5").compareTo(response.getMaximumRetailPrice()));
    assertEquals(0, new BigDecimal("55.25").compareTo(response.getCostPrice()));
    assertEquals("6", response.getSgst());
    assertEquals("6", response.getCgst());
    assertEquals(0, new BigDecimal("3").compareTo(response.getSaleAdditionalDiscount()));
  }

  @Test
  void aNonPriceEditStillReturnsTheStoredPrices() {
    UpdateInventoryRequest request = new UpdateInventoryRequest();
    request.setLocation("RACK-2");

    InventoryDetailResponse response = inventoryService.update("lot-1", request, "s1");

    assertEquals("RACK-2", response.getLocation());
    assertEquals(0, new BigDecimal("77.5").compareTo(response.getPriceToRetail()));
  }
}
