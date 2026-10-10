package com.inventory.plugins.cafe;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.menu.MenuItem;
import com.inventory.pluginengine.menu.MenuRate;
import com.inventory.pluginengine.menu.MenuSection;
import com.inventory.pluginengine.menu.ShopMenu;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What a menu with portions has to look like before it is allowed onto a shop. */
class CafeMenuVerticalValidatorRatesTest {

  private final CafeMenuVerticalValidator validator = new CafeMenuVerticalValidator();

  @Test
  void aPortionedItemNeedsNoSinglePrice() {
    // sellingPrice is normalised to null at write when portions exist. Demanding a positive one
    // here would make every portioned item unsaveable.
    MenuItem item = item(rate("half", "Half", "180"), rate("full", "Full", "320"));
    item.setSellingPrice(null);

    assertDoesNotThrow(() -> validate(item));
  }

  @Test
  void anUnportionedItemStillNeedsAPositiveSinglePrice() {
    MenuItem item = item();
    item.setSellingPrice(null);

    ValidationException refused = assertThrows(ValidationException.class, () -> validate(item));
    assertTrue(refused.getMessage().contains("selling price"), refused.getMessage());
  }

  @Test
  void aBlankPortionIdIsRejected() {
    assertThrows(
        ValidationException.class, () -> validate(item(rate("  ", "Half", "180"))));
  }

  @Test
  void aBlankPortionNameIsRejected() {
    assertThrows(ValidationException.class, () -> validate(item(rate("half", "   ", "180"))));
  }

  @Test
  void aZeroOrNegativePortionPriceIsRejected() {
    assertThrows(ValidationException.class, () -> validate(item(rate("half", "Half", "0"))));
    assertThrows(ValidationException.class, () -> validate(item(rate("half", "Half", "-1"))));
    MenuItem noPrice = item(rate("half", "Half", "180"));
    noPrice.getRates().get(0).setPrice(null);
    assertThrows(ValidationException.class, () -> validate(noPrice));
  }

  @Test
  void twoPortionsSharingAnIdAreRejected() {
    // The id is what a sellableRef names; two answering to it makes which one was sold a guess.
    ValidationException refused =
        assertThrows(
            ValidationException.class,
            () -> validate(item(rate("half", "Half", "180"), rate("half", "Full", "320"))));
    assertTrue(refused.getMessage().contains("Duplicate portion id"), refused.getMessage());
  }

  @Test
  void namesDifferingOnlyByCaseOrWhitespaceAreRejected() {
    assertThrows(
        ValidationException.class,
        () -> validate(item(rate("half", "Half", "180"), rate("half-2", "half", "200"))));
    assertThrows(
        ValidationException.class,
        () -> validate(item(rate("half", "Half", "180"), rate("half-2", "  Half  ", "200"))));
  }

  @Test
  void theShopsOwnCasingIsPreservedForDisplay() {
    MenuItem item = item(rate("half", "  HALF plate ", "180"));

    validate(item);

    assertEquals(
        "  HALF plate ",
        item.getRates().get(0).getName(),
        "comparison trims and lower-cases; it never rewrites what the shop typed");
  }

  private void validate(MenuItem item) {
    MenuSection section = new MenuSection();
    section.setId("sec-1");
    section.setItems(new ArrayList<>(List.of(item)));
    ShopMenu menu = new ShopMenu();
    menu.setShopId("shop-1");
    menu.setVerticalId("cafe");
    menu.setSections(new ArrayList<>(List.of(section)));
    validator.validate(menu, null, "shop-1");
  }

  private static MenuItem item(MenuRate... rates) {
    MenuItem item = new MenuItem();
    item.setId("item-bc");
    item.setName("Butter Chicken");
    item.setSellingPrice(new BigDecimal("250"));
    if (rates.length > 0) {
      item.setRates(new ArrayList<>(Arrays.asList(rates)));
      item.setSellingPrice(null);
    }
    return item;
  }

  private static MenuRate rate(String id, String name, String price) {
    MenuRate rate = new MenuRate();
    rate.setId(id);
    rate.setName(name);
    rate.setPrice(new BigDecimal(price));
    return rate;
  }
}
