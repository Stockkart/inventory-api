package com.inventory.plugins.cafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * A kitchen ticket's value over a receipt is the preparation instruction it carries. This pins
 * that {@code CartLineInput.note} arrives on the snapshot trimmed, and that a blank note becomes
 * null rather than printing as an empty instruction line.
 */
class CafeMenuCartLineContributorNoteTest {

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
  void noteIsTrimmedOntoTheSnapshot() {
    MenuItem menuItem = new MenuItem();
    menuItem.setId("item-bar");
    menuItem.setName("Mojito");
    menuItem.setSellingPrice(new BigDecimal("199"));
    menuItem.setAvailable(true);
    when(shopMenuLookup.findMenuItem(SHOP_ID, "item-bar")).thenReturn(Optional.of(menuItem));

    CartLineInput input =
        CartLineInput.builder()
            .sellableRef("menu:item-bar")
            .quantity(2)
            .note("  no onion  ")
            .build();
    List<CartLineSnapshot> lines =
        contributor.buildLines(
            List.of(input), CartBuildContext.builder().shopId(SHOP_ID).build());

    assertEquals(1, lines.size());
    assertEquals("no onion", lines.get(0).getNote());
  }

  @Test
  void blankNoteBecomesNull() {
    MenuItem menuItem = new MenuItem();
    menuItem.setId("item-bar");
    menuItem.setName("Mojito");
    menuItem.setSellingPrice(new BigDecimal("199"));
    menuItem.setAvailable(true);
    when(shopMenuLookup.findMenuItem(SHOP_ID, "item-bar")).thenReturn(Optional.of(menuItem));

    CartLineInput input =
        CartLineInput.builder().sellableRef("menu:item-bar").quantity(2).note("   ").build();
    List<CartLineSnapshot> lines =
        contributor.buildLines(
            List.of(input), CartBuildContext.builder().shopId(SHOP_ID).build());

    assertEquals(1, lines.size());
    assertNull(lines.get(0).getNote());
  }

  @Test
  void negativeQuantityCancellationLineAlsoCarriesTrimmedNote() {
    MenuItem menuItem = new MenuItem();
    menuItem.setId("item-bar");
    menuItem.setName("Mojito");
    when(shopMenuLookup.findMenuItem(SHOP_ID, "item-bar")).thenReturn(Optional.of(menuItem));

    CartLineInput input =
        CartLineInput.builder()
            .sellableRef("menu:item-bar")
            .quantity(-1)
            .note("  no onion  ")
            .build();
    List<CartLineSnapshot> lines =
        contributor.buildLines(
            List.of(input), CartBuildContext.builder().shopId(SHOP_ID).build());

    assertEquals(1, lines.size());
    assertEquals("no onion", lines.get(0).getNote());
  }
}
