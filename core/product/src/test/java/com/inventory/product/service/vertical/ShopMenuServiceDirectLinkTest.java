package com.inventory.product.service.vertical;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.VerticalPlugin;
import com.inventory.pluginengine.menu.MenuItem;
import com.inventory.pluginengine.menu.MenuSection;
import com.inventory.pluginengine.menu.MenuSellMode;
import com.inventory.pluginengine.menu.MenuVerticalValidator;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.ShopMenuDocument;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.ShopMenuRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.request.UpsertShopMenuRequest;
import com.inventory.product.validation.ShopValidator;
import com.inventory.user.service.UserShopMembershipService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A save must not overwrite a menu it never read, and deleting a stock lot must not make the menu
 * that still places it unsaveable.
 */
class ShopMenuServiceDirectLinkTest {

  private ShopMenuRepository shopMenuRepository;
  private InventoryRepository inventoryRepository;
  private ShopMenuService service;

  @BeforeEach
  void setUp() {
    shopMenuRepository = mock(ShopMenuRepository.class);
    ShopRepository shopRepository = mock(ShopRepository.class);
    inventoryRepository = mock(InventoryRepository.class);
    PluginRegistry pluginRegistry = mock(PluginRegistry.class);
    SchemaLoader schemaLoader = mock(SchemaLoader.class);
    ShopValidator shopValidator = mock(ShopValidator.class);
    UserShopMembershipService membershipService = mock(UserShopMembershipService.class);

    Shop shop = new Shop();
    shop.setVerticalId("cafe");
    when(shopRepository.findById("shop-1")).thenReturn(Optional.of(shop));
    when(membershipService.hasAccess(any(), any())).thenReturn(true);
    VerticalPlugin plugin = mock(VerticalPlugin.class);
    MenuVerticalValidator noop = mock(MenuVerticalValidator.class);
    when(plugin.getMenuVerticalValidator()).thenReturn(Optional.of(noop));
    when(pluginRegistry.find("cafe")).thenReturn(Optional.of(plugin));
    when(shopMenuRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    service =
        new ShopMenuService(
            shopMenuRepository,
            shopRepository,
            inventoryRepository,
            pluginRegistry,
            schemaLoader,
            shopValidator,
            membershipService);
  }

  @Test
  void aSaveWithoutRevisionOverAStoredMenuIsRefused() {
    storedMenu(3, List.of());
    ValidationException ex =
        assertThrows(
            ValidationException.class,
            () -> service.upsertShopMenu("shop-1", "u1", request(null, List.of())));
    assertTrue(ex.getMessage().contains("reload the menu"));
  }

  @Test
  void aLinkWhoseLotWasDeletedIsKeptWhenItWasAlreadyStored() {
    storedMenu(3, List.of(sectionWith(direct("i1", "Lassi", "inv-gone"))));
    when(inventoryRepository.findById("inv-gone")).thenReturn(Optional.empty());
    assertDoesNotThrow(
        () ->
            service.upsertShopMenu(
                "shop-1", "u1", request(3, List.of(sectionWith(direct("i1", "Lassi", "inv-gone"))))));
  }

  @Test
  void aNewLinkToAMissingLotIsRefused() {
    storedMenu(3, List.of());
    when(inventoryRepository.findById("inv-gone")).thenReturn(Optional.empty());
    assertThrows(
        ValidationException.class,
        () ->
            service.upsertShopMenu(
                "shop-1", "u1", request(3, List.of(sectionWith(direct("i1", "Lassi", "inv-gone"))))));
  }

  private void storedMenu(int revision, List<MenuSection> sections) {
    ShopMenuDocument doc = new ShopMenuDocument();
    doc.setId("menu-1");
    doc.setShopId("shop-1");
    doc.setVerticalId("cafe");
    doc.setRevision(revision);
    doc.setSections(new ArrayList<>(sections));
    when(shopMenuRepository.findByShopIdAndVerticalId("shop-1", "cafe"))
        .thenReturn(Optional.of(doc));
  }

  private static UpsertShopMenuRequest request(Integer revision, List<MenuSection> sections) {
    UpsertShopMenuRequest req = new UpsertShopMenuRequest();
    req.setRevision(revision);
    req.setSections(new ArrayList<>(sections));
    return req;
  }

  private static MenuSection sectionWith(MenuItem... items) {
    MenuSection s = new MenuSection();
    s.setId("bev");
    s.setTitle("Beverages");
    s.setItems(new ArrayList<>(List.of(items)));
    return s;
  }

  private static MenuItem direct(String id, String name, String inventoryId) {
    MenuItem item = new MenuItem();
    item.setId(id);
    item.setName(name);
    item.setSellMode(MenuSellMode.direct);
    item.setInventoryId(inventoryId);
    return item;
  }
}
