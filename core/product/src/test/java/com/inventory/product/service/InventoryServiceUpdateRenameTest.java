package com.inventory.product.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.pricing.api.LotPricing;
import com.inventory.pricing.api.InventoryPricing;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.catalog.InventoryProductReadHandler;
import com.inventory.product.domain.model.pricing.InventoryPricingReadHandler;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.mapper.InventoryMapper;
import com.inventory.product.rest.dto.request.UpdateInventoryRequest;
import com.inventory.product.service.vertical.InventoryVerticalExtensionHandler;
import com.inventory.product.service.vertical.InventoryVerticalValidationHandler;
import com.inventory.product.validation.InventoryValidator;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mapstruct.factory.Mappers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Renaming a lot in the Edit dialog moves it to another catalog product. Once the lot is saved, the
 * product it left is handed to {@link ProductService#retireIfUnused}, which deletes it when nothing
 * uses it any more, so the shared barcode does not print a second sticker from the old product.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryServiceUpdateRenameTest {

  @Mock private InventoryRepository inventoryRepository;
  @Mock private InventoryValidator inventoryValidator;
  @Mock private ProductService productService;
  @Mock private InventoryVerticalValidationHandler inventoryVerticalValidationHandler;
  @Mock private InventoryVerticalExtensionHandler inventoryVerticalExtensionHandler;
  @Mock private InventoryProductReadHandler inventoryProductReadHandler;
  @Mock private InventoryPricing pricingPort;
  @Spy private InventoryMapper inventoryMapper = Mappers.getMapper(InventoryMapper.class);
  @InjectMocks private InventoryService inventoryService;

  /** Pricing record as stored; this test does not look at it. */
  private final LotPricing stored = new LotPricing(new BigDecimal("99.5"), new BigDecimal("55.25"),
      null, new BigDecimal("77.5"), null, null, null, new BigDecimal("3"), null, null, null, "6",
      "6", null);

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
    // The rename makes the lot's identity another product's.
    when(productService.resolveForRegistration(eq("p1"), any(), eq("s1"), anyBoolean()))
        .thenReturn("p2");
    when(productService.findInShop(eq("s1"), anyString())).thenReturn(Optional.empty());
    when(inventoryVerticalExtensionHandler.mergeDetail(eq("s1"), any(), any()))
        .thenAnswer(inv -> inv.getArgument(2));

    when(pricingPort.findById("pr-1")).thenReturn(Optional.of(stored));

    InventoryPricingReadHandler pricingReadHandler = new InventoryPricingReadHandler();
    ReflectionTestUtils.setField(pricingReadHandler, "pricingPort", pricingPort);
    ReflectionTestUtils.setField(
        inventoryService, "inventoryPricingReadHandler", pricingReadHandler);
  }

  @Test
  void aRenameRetiresTheProductTheLotLeftAfterTheLotIsSaved() {
    UpdateInventoryRequest request = new UpdateInventoryRequest();
    request.setName("LEGGINGS ELASTANO N (S)");

    inventoryService.update("lot-1", request, "s1", "u1");

    InOrder order = inOrder(inventoryRepository, productService);
    order.verify(inventoryRepository).save(any(Inventory.class));
    order.verify(productService).retireIfUnused("p1", "p2", "s1");
  }

  @Test
  void anEditThatKeepsTheProductPassesTheSameIdSoNothingIsRetired() {
    when(productService.resolveForRegistration(eq("p1"), any(), eq("s1"), anyBoolean()))
        .thenReturn("p1");
    UpdateInventoryRequest request = new UpdateInventoryRequest();
    request.setLocation("RACK-2");

    inventoryService.update("lot-1", request, "s1", "u1");

    verify(productService).retireIfUnused("p1", "p1", "s1");
  }

  @Test
  void aFailedCleanUpStillReturnsTheSavedEdit() {
    doThrow(new DataAccessResourceFailureException("mongo down"))
        .when(productService).retireIfUnused("p1", "p2", "s1");
    UpdateInventoryRequest request = new UpdateInventoryRequest();
    request.setName("LEGGINGS ELASTANO N (S)");

    org.junit.jupiter.api.Assertions.assertEquals(
        "p2", inventoryService.update("lot-1", request, "s1", "u1").getProductId());
  }
}
