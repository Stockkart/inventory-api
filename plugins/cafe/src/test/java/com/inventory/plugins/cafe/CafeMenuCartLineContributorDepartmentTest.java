package com.inventory.plugins.cafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.pluginengine.cart.CartBuildContext;
import com.inventory.pluginengine.cart.CartLineInput;
import com.inventory.pluginengine.cart.CartLineSnapshot;
import com.inventory.pluginengine.integration.InventoryCartLookup;
import com.inventory.pluginengine.integration.ShopMenuLookup;
import com.inventory.pluginengine.menu.MenuItem;
import com.inventory.plugins.cafe.repository.CafeInventoryExtensionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The kitchen station must be frozen onto the cart line when it is added — resolved from the
 * menu item's department, defaulting to KITCHEN, never left null.
 */
class CafeMenuCartLineContributorDepartmentTest {

  private static final String SHOP_ID = "shop-1";

  private ShopMenuLookup shopMenuLookup;
  private CafeMenuCartLineContributor contributor;

  @BeforeEach
  void setUp() {
    shopMenuLookup = mock(ShopMenuLookup.class);
    InventoryCartLookup inventoryCartLookup = mock(InventoryCartLookup.class);
    CafeInventoryExtensionRepository extensionRepository =
        mock(CafeInventoryExtensionRepository.class);
    contributor =
        new CafeMenuCartLineContributor(shopMenuLookup, inventoryCartLookup, extensionRepository);
  }

  @Test
  void menuItemWithDepartmentFreezesUppercasedStation() {
    MenuItem menuItem = new MenuItem();
    menuItem.setId("item-bar");
    menuItem.setName("Mojito");
    menuItem.setSellingPrice(new BigDecimal("199"));
    menuItem.setAvailable(true);
    menuItem.setDepartment("bar");
    when(shopMenuLookup.findMenuItem(SHOP_ID, "item-bar")).thenReturn(Optional.of(menuItem));

    CartLineInput input =
        CartLineInput.builder().sellableRef("menu:item-bar").quantity(2).build();
    List<CartLineSnapshot> lines =
        contributor.buildLines(
            List.of(input), CartBuildContext.builder().shopId(SHOP_ID).build());

    assertEquals(1, lines.size());
    assertEquals("BAR", lines.get(0).getDepartment());
  }

  @Test
  void menuItemWithNoDepartmentFreezesKitchenDefault() {
    MenuItem menuItem = new MenuItem();
    menuItem.setId("item-kitchen");
    menuItem.setName("Fries");
    menuItem.setSellingPrice(new BigDecimal("99"));
    menuItem.setAvailable(true);
    when(shopMenuLookup.findMenuItem(SHOP_ID, "item-kitchen"))
        .thenReturn(Optional.of(menuItem));

    CartLineInput input =
        CartLineInput.builder().sellableRef("menu:item-kitchen").quantity(1).build();
    List<CartLineSnapshot> lines =
        contributor.buildLines(
            List.of(input), CartBuildContext.builder().shopId(SHOP_ID).build());

    assertEquals(1, lines.size());
    assertEquals("KITCHEN", lines.get(0).getDepartment());
  }

  @Test
  void negativeQuantityCancellationLineAlsoFreezesStation() {
    MenuItem menuItem = new MenuItem();
    menuItem.setId("item-bar");
    menuItem.setName("Mojito");
    menuItem.setDepartment("bar");
    when(shopMenuLookup.findMenuItem(SHOP_ID, "item-bar")).thenReturn(Optional.of(menuItem));

    CartLineInput input =
        CartLineInput.builder().sellableRef("menu:item-bar").quantity(-1).build();
    List<CartLineSnapshot> lines =
        contributor.buildLines(
            List.of(input), CartBuildContext.builder().shopId(SHOP_ID).build());

    assertEquals(1, lines.size());
    assertEquals("BAR", lines.get(0).getDepartment());
  }
}
