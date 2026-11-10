package com.inventory.pluginengine.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MenuDirectLinksTest {

  @Test
  void aDirectItemLosesAnyMenuPriceTheClientSent() {
    MenuRate half = new MenuRate();
    half.setId("half");
    half.setName("Half");
    half.setPrice(new BigDecimal("50"));
    MenuItem item = new MenuItem();
    item.setSellMode(MenuSellMode.direct);
    item.setInventoryId("  inv-1 ");
    item.setSellingPrice(new BigDecimal("100"));
    item.setRates(List.of(half));
    item.setCgst("9");
    item.setSgst("9");

    MenuDirectLinks.normalize(item);

    assertNull(item.getSellingPrice());
    assertNull(item.getRates());
    assertNull(item.getCgst());
    assertNull(item.getSgst());
    assertEquals("inv-1", item.getInventoryId());
  }

  @Test
  void aMenuItemIsLeftExactlyAsItWas() {
    MenuItem item = new MenuItem();
    item.setSellMode(MenuSellMode.menu);
    item.setSellingPrice(new BigDecimal("100"));

    MenuDirectLinks.normalize(item);

    assertEquals(new BigDecimal("100"), item.getSellingPrice());
    assertFalse(MenuDirectLinks.isDirect(item));
  }
}
