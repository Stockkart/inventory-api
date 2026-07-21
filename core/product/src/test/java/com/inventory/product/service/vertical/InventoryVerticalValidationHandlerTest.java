package com.inventory.product.service.vertical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.pluginengine.InventoryExtensionRepository;
import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.VerticalPlugin;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.request.CreateInventoryRequest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Verifies the create-time guard that fails when a vertical's extension table is missing. */
@ExtendWith(MockitoExtension.class)
class InventoryVerticalValidationHandlerTest {

  private static final String SHOP_ID = "shop-1";
  private static final String VERTICAL = "medical";

  @Mock private ShopRepository shopRepository;
  @Mock private PluginRegistry pluginRegistry;
  @Mock private SchemaLoader schemaLoader;
  @Mock private InventoryVerticalExtensionHandler extensionHandler;
  @Mock private VerticalPlugin plugin;
  @Mock private InventoryExtensionRepository extensionRepository;

  private InventoryVerticalValidationHandler handler;

  @BeforeEach
  void setUp() {
    handler =
        new InventoryVerticalValidationHandler(
            shopRepository, pluginRegistry, schemaLoader, extensionHandler);
  }

  private Shop medicalShop() {
    Shop shop = new Shop();
    shop.setVerticalId(VERTICAL);
    shop.setPluginVersion("1.0.0");
    return shop;
  }

  @Test
  void validateCreateThrowsWhenExtensionTableProvisioningFails() {
    when(shopRepository.findById(SHOP_ID)).thenReturn(Optional.of(medicalShop()));
    when(pluginRegistry.find(VERTICAL)).thenReturn(Optional.of(plugin));
    when(plugin.getInventoryExtensionRepository()).thenReturn(Optional.of(extensionRepository));
    doThrow(new IllegalStateException("mongo down"))
        .when(extensionRepository)
        .ensureBackingCollectionExists();

    BaseException ex =
        assertThrows(
            BaseException.class,
            () -> handler.validateCreate(SHOP_ID, new CreateInventoryRequest()));

    assertEquals(ErrorCode.VERTICAL_EXTENSION_UNAVAILABLE, ex.getErrorCode());
    assertEquals(500, ex.getErrorCode().getHttpStatus().value());
  }

  @Test
  void validateCreateProvisionsExtensionTableAndPasses() {
    when(shopRepository.findById(SHOP_ID)).thenReturn(Optional.of(medicalShop()));
    when(pluginRegistry.find(VERTICAL)).thenReturn(Optional.of(plugin));
    when(plugin.getInventoryExtensionRepository()).thenReturn(Optional.of(extensionRepository));
    doNothing().when(extensionRepository).ensureBackingCollectionExists();
    when(plugin.getInventoryValidator()).thenReturn(Optional.empty());

    assertDoesNotThrow(() -> handler.validateCreate(SHOP_ID, new CreateInventoryRequest()));

    verify(extensionRepository).ensureBackingCollectionExists();
  }

  @Test
  void validateUpdateDoesNotTouchExtensionTable() {
    Shop shop = medicalShop();
    when(shopRepository.findById(SHOP_ID)).thenReturn(Optional.of(shop));
    when(pluginRegistry.find(VERTICAL)).thenReturn(Optional.of(plugin));
    lenient().when(plugin.getInventoryValidator()).thenReturn(Optional.empty());

    handler.validateUpdate(SHOP_ID, new com.inventory.product.domain.model.Inventory(), null);

    verify(extensionRepository, never()).ensureBackingCollectionExists();
  }
}
