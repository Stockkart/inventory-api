package com.inventory.pluginengine.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a menu write does to an item's portions: it freezes a readable slug onto each new one, and
 * it drops a single price the portions have replaced.
 *
 * <p>The freezing is the load-bearing half. The id is inside every {@code sellableRef} the
 * portion has ever been sold under, so a write that re-derived it from the current name would
 * orphan every one of them the first time a shop renamed a portion.
 */
class MenuRatesTest {

  @Test
  void aNewPortionGetsAReadableSlugFromItsName() {
    MenuItem item = portioned(rate(null, "Half", "180"), rate(null, "Full", "320"));

    MenuRates.normalize(item);

    assertEquals(List.of("half", "full"), item.getRates().stream().map(MenuRate::getId).toList());
    assertEquals(
        List.of("Half", "Full"),
        item.getRates().stream().map(MenuRate::getName).toList(),
        "the shop's own casing is kept for display");
  }

  @Test
  void aPortionsIdIsFrozenAndSurvivesEveryRename() {
    MenuItem item = portioned(rate("half", "Half", "180"));

    item.getRates().get(0).setName("1/2 Plate");
    MenuRates.normalize(item);

    assertEquals(
        "half",
        item.getRates().get(0).getId(),
        "the rename is cosmetic; every sellableRef ever written says @half");
    assertEquals("1/2 Plate", item.getRates().get(0).getName());
  }

  @Test
  void aCollisionWithinOneItemTakesANumericSuffix() {
    MenuItem item = portioned(rate(null, "Half", "180"), rate(null, "half", "200"));

    MenuRates.normalize(item);

    assertEquals(List.of("half", "half-2"), item.getRates().stream().map(MenuRate::getId).toList());
  }

  @Test
  void aNewPortionNeverStealsASlugAnExistingOneAlreadyOwns() {
    // The new portion is listed FIRST and would slug to "half"; the existing one below it is
    // already sold under that id. Assigning ids in list order would move the old sales onto the
    // new portion.
    MenuItem item = portioned(rate(null, "Half", "200"), rate("half", "Half Plate", "180"));

    MenuRates.normalize(item);

    assertEquals("half-2", item.getRates().get(0).getId());
    assertEquals("half", item.getRates().get(1).getId());
  }

  @Test
  void aNameOfNothingButPunctuationStillYieldsAParseableId() {
    MenuItem item = portioned(rate(null, "1/2", "180"), rate(null, "???", "90"));

    MenuRates.normalize(item);

    assertEquals("1-2", item.getRates().get(0).getId());
    assertEquals(
        "portion",
        item.getRates().get(1).getId(),
        "never the empty string, which is not a parseable sellableRef variant");
  }

  @Test
  void sellingPriceIsNulledAtWriteRatherThanIgnoredAtRead() {
    MenuItem item = portioned(rate(null, "Half", "180"));
    item.setSellingPrice(new BigDecimal("250"));

    MenuRates.normalize(item);

    assertNull(
        item.getSellingPrice(),
        "a price nothing honours is a price a future reader trusts by mistake");
  }

  @Test
  void anItemWithNoPortionsKeepsItsSinglePrice() {
    MenuItem item = new MenuItem();
    item.setName("Chai");
    item.setSellingPrice(new BigDecimal("20"));

    MenuRates.normalize(item);

    assertEquals(new BigDecimal("20"), item.getSellingPrice());
    assertNull(item.getRates(), "an empty list is normalised away rather than stored");
    assertTrue(!MenuRates.isPortioned(item));
  }

  @Test
  void findByIdNeverFallsBackToTheFirstPortion() {
    MenuItem item = portioned(rate("half", "Half", "180"), rate("full", "Full", "320"));

    assertEquals("Full", MenuRates.findById(item, "full").orElseThrow().getName());
    assertTrue(MenuRates.findById(item, "qtr").isEmpty());
    assertTrue(MenuRates.findById(item, null).isEmpty());
  }

  private static MenuItem portioned(MenuRate... rates) {
    MenuItem item = new MenuItem();
    item.setId("item-1");
    item.setName("Butter Chicken");
    item.setRates(new ArrayList<>(List.of(rates)));
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
