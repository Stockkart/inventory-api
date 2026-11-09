package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.catalog.InventoryProductReadHandler;
import com.inventory.product.domain.model.pricing.InventoryPricingReadHandler;
import com.inventory.product.domain.model.enums.DiscountApplicable;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.mapper.InventoryMapper;
import com.inventory.product.rest.dto.request.UpdateInventoryRequest;
import com.inventory.product.service.vertical.InventoryVerticalExtensionHandler;
import com.inventory.product.service.vertical.InventoryVerticalValidationHandler;
import com.inventory.product.validation.InventoryValidator;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** A lot update clears exactly the fields named in clearFields; a null field alone keeps its value. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryServiceClearFieldsTest {

  @Mock private InventoryRepository inventoryRepository;
  @Mock private InventoryValidator inventoryValidator;
  @Mock private ProductService productService;
  @Mock private InventoryVerticalValidationHandler inventoryVerticalValidationHandler;
  @Mock private InventoryVerticalExtensionHandler inventoryVerticalExtensionHandler;
  @Mock private InventoryProductReadHandler inventoryProductReadHandler;
  // Re-reads pricing after the save once inventory-api #309 is in; unused before it.
  @Mock private InventoryPricingReadHandler inventoryPricingReadHandler;
  @Spy private InventoryMapper inventoryMapper = Mappers.getMapper(InventoryMapper.class);
  @InjectMocks private InventoryService inventoryService;

  @BeforeEach
  void setUp() {
    Inventory lot = new Inventory();
    lot.setId("lot-1");
    lot.setShopId("s1");
    lot.setProductId("p1");
    lot.setLocation("E4");
    lot.setHsn("30049011");
    lot.setThresholdCount(9);
    lot.setDiscountApplicable(DiscountApplicable.DISCOUNT);
    lot.setSaleAdditionalDiscount(new BigDecimal("3"));

    when(inventoryRepository.findById("lot-1")).thenReturn(Optional.of(lot));
    when(inventoryRepository.save(any(Inventory.class))).thenAnswer(inv -> inv.getArgument(0));
    when(productService.resolveForRegistration(anyString(), any(), eq("s1"))).thenReturn("p1");
    when(productService.findInShop(eq("s1"), anyString())).thenReturn(Optional.empty());
    when(inventoryVerticalExtensionHandler.mergeDetail(eq("s1"), any(), any()))
        .thenAnswer(inv -> inv.getArgument(2));
  }

  @Test
  void theNamedFieldsAreCleared() {
    UpdateInventoryRequest request = new UpdateInventoryRequest();
    request.setClearFields(
        Set.of("location", "thresholdCount", "discountApplicable", "saleAdditionalDiscount"));

    inventoryService.update("lot-1", request, "s1", "u1");

    ArgumentCaptor<Inventory> saved = ArgumentCaptor.forClass(Inventory.class);
    verify(inventoryRepository).save(saved.capture());
    assertNull(saved.getValue().getLocation());
    assertNull(saved.getValue().getThresholdCount());
    assertNull(saved.getValue().getDiscountApplicable());
    assertNull(saved.getValue().getSaleAdditionalDiscount());
    assertEquals("30049011", saved.getValue().getHsn());
  }

  @Test
  void aNullFieldWithoutAClearKeepsItsValue() {
    UpdateInventoryRequest request = new UpdateInventoryRequest();
    request.setLocation(null);
    request.setThresholdCount(null);
    request.setHsn("30049099");

    inventoryService.update("lot-1", request, "s1", "u1");

    ArgumentCaptor<Inventory> saved = ArgumentCaptor.forClass(Inventory.class);
    verify(inventoryRepository).save(saved.capture());
    assertEquals("E4", saved.getValue().getLocation());
    assertEquals(9, saved.getValue().getThresholdCount());
    assertEquals("30049099", saved.getValue().getHsn());
  }
}
