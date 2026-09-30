package com.inventory.plugins.cafe;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.menu.MenuItem;
import com.inventory.pluginengine.menu.MenuSection;
import com.inventory.pluginengine.menu.MenuSellMode;
import com.inventory.pluginengine.menu.ShopMenu;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A direct item places a stock lot in a section: it has no menu price, and a lot is placed once. */
class CafeMenuVerticalValidatorDirectTest {

  private final CafeMenuVerticalValidator validator = new CafeMenuVerticalValidator();

  private static MenuItem direct(String id, String name, String inventoryId) {
    MenuItem item = new MenuItem();
    item.setId(id);
    item.setName(name);
    item.setSellMode(MenuSellMode.direct);
    item.setInventoryId(inventoryId);
    return item;
  }

  private static MenuSection section(String title, MenuItem... items) {
    MenuSection s = new MenuSection();
    s.setId(title);
    s.setTitle(title);
    s.setItems(List.of(items));
    return s;
  }

  private static ShopMenu menu(MenuSection... sections) {
    ShopMenu m = new ShopMenu();
    m.setSections(List.of(sections));
    return m;
  }

  @Test
  void aDirectItemNeedsNoMenuPrice() {
    assertDoesNotThrow(
        () ->
            validator.validate(
                menu(section("Beverages", direct("i1", "Lassi", "inv-1"))), null, "shop-1"));
  }

  @Test
  void aDirectItemWithoutInventoryIsRefused() {
    ValidationException ex =
        assertThrows(
            ValidationException.class,
            () ->
                validator.validate(
                    menu(section("Beverages", direct("i1", "Lassi", " "))), null, "shop-1"));
    assertTrue(ex.getMessage().contains("Lassi"));
  }

  @Test
  void oneStockItemLinkedTwiceIsRefusedNamingWhereItAlreadyIs() {
    ValidationException ex =
        assertThrows(
            ValidationException.class,
            () ->
                validator.validate(
                    menu(
                        section("Beverages", direct("i1", "Lassi", "inv-1")),
                        section("Drinks", direct("i2", "Lassi", "inv-1"))),
                    null,
                    "shop-1"));
    assertEquals("Lassi is already in Beverages", ex.getMessage());
  }
}
