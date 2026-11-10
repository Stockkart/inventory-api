package com.inventory.plugins.cafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.cart.CartBuildContext;
import com.inventory.pluginengine.cart.CartLineInput;
import com.inventory.pluginengine.cart.CartLineSnapshot;
import com.inventory.pluginengine.integration.InventoryCartLookup;
import com.inventory.pluginengine.integration.InventoryLineSnapshot;
import com.inventory.pluginengine.integration.ShopMenuLookup;
import com.inventory.pluginengine.menu.MenuItem;
import com.inventory.pluginengine.menu.MenuSellMode;
import com.inventory.plugins.cafe.repository.CafeInventoryExtensionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A stock lot placed in a menu section takes that placement's station onto its cart line; price and
 * stock stay the lot's. An unplaced lot keeps the kitchen default.
 */
class CafeMenuCartLineContributorLinkedStockTest {

  private static final String SHOP_ID = "shop-1";

  private ShopMenuLookup shopMenuLookup;
  private InventoryCartLookup inventoryCartLookup;
  private CafeInventoryExtensionRepository extensionRepository;
  private CafeMenuCartLineContributor contributor;

  @BeforeEach
  void setUp() {
    shopMenuLookup = mock(ShopMenuLookup.class);
    inventoryCartLookup = mock(InventoryCartLookup.class);
    extensionRepository = mock(CafeInventoryExtensionRepository.class);
    contributor =
        new CafeMenuCartLineContributor(shopMenuLookup, inventoryCartLookup, extensionRepository);
  }

  @Test
  void aLinkedStockLineFreezesTheLinksStation() {
    stubSellableLot("inv-1");
    linkWithStation("inv-1", "bar");

    CartLineSnapshot line = build("inventory:inv-1", 1);

    assertEquals("BAR", line.getDepartment());
    assertEquals("inventory:inv-1", line.getSellableRef());
  }

  @Test
  void aNoTicketLinkFreezesNone() {
    stubSellableLot("inv-1");
    linkWithStation("inv-1", "none");

    assertEquals("NONE", build("inventory:inv-1", 1).getDepartment());
  }

  @Test
  void anUnlinkedStockLineKeepsTheKitchenDefault() {
    stubSellableLot("inv-1");
    when(shopMenuLookup.findDirectLink(SHOP_ID, "inv-1")).thenReturn(Optional.empty());

    assertEquals("KITCHEN", build("inventory:inv-1", 1).getDepartment());
  }

  @Test
  void aTakebackLineAlsoCarriesTheLinksStation() {
    stubSellableLot("inv-1");
    linkWithStation("inv-1", "BAR");

    assertEquals("BAR", build("inventory:inv-1", -1).getDepartment());
  }

  @Test
  void aPlacedStockItemCannotBeSoldAsAFreeMenuLine() {
    // A stale sell screen (loaded before the frontend knew about placements) would post the
    // placement's menu ref. Priced from the menu that is Rs 0 with no stock taken off.
    MenuItem link = new MenuItem();
    link.setId("l1");
    link.setName("Lassi");
    link.setSellMode(MenuSellMode.direct);
    link.setInventoryId("inv-1");
    link.setAvailable(true);
    when(shopMenuLookup.findMenuItem(SHOP_ID, "l1")).thenReturn(Optional.of(link));

    ValidationException ex =
        assertThrows(ValidationException.class, () -> build("menu:l1", 1));
    assertTrue(ex.getMessage().contains("reload the sell screen"));
  }

  @Test
  void aPlacedStockMenuLineAlreadyOnTheBillCanStillBeTakenBack() {
    MenuItem link = new MenuItem();
    link.setId("l1");
    link.setName("Lassi");
    link.setSellMode(MenuSellMode.direct);
    link.setInventoryId("inv-1");
    when(shopMenuLookup.findMenuItem(SHOP_ID, "l1")).thenReturn(Optional.of(link));

    assertEquals(-1, build("menu:l1", -1).getBaseQuantity());
  }

  private void stubSellableLot(String inventoryId) {
    when(extensionRepository.findByInventoryId(SHOP_ID, inventoryId))
        .thenReturn(Optional.of(Map.of("sellDirect", "yes")));
    when(inventoryCartLookup.findForShop(SHOP_ID, inventoryId))
        .thenReturn(
            Optional.of(
                InventoryLineSnapshot.builder()
                    .inventoryId(inventoryId)
                    .name("Lassi")
                    .priceToRetail(new BigDecimal("40"))
                    .availableBaseCount(10)
                    .baseUnit("BTL")
                    .build()));
  }

  private void linkWithStation(String inventoryId, String department) {
    MenuItem link = new MenuItem();
    link.setSellMode(MenuSellMode.direct);
    link.setInventoryId(inventoryId);
    link.setDepartment(department);
    when(shopMenuLookup.findDirectLink(SHOP_ID, inventoryId)).thenReturn(Optional.of(link));
  }

  private CartLineSnapshot build(String ref, int qty) {
    CartLineInput input = CartLineInput.builder().sellableRef(ref).quantity(qty).build();
    List<CartLineSnapshot> lines =
        contributor.buildLines(List.of(input), CartBuildContext.builder().shopId(SHOP_ID).build());
    return lines.get(0);
  }
}
