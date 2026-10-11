package com.inventory.plugins.cafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.cart.CartBuildContext;
import com.inventory.pluginengine.cart.CartLineInput;
import com.inventory.pluginengine.cart.CartLineSnapshot;
import com.inventory.pluginengine.integration.InventoryCartLookup;
import com.inventory.pluginengine.integration.ShopMenuLookup;
import com.inventory.pluginengine.menu.MenuItem;
import com.inventory.pluginengine.menu.MenuRate;
import com.inventory.plugins.cafe.repository.CafeInventoryExtensionRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Selling a dish by the portion.
 *
 * <p>The refusals are the substance here. A portioned item sold with no portion named cannot be
 * priced at all; a portion the menu no longer has must be refused rather than quietly served as
 * some other portion, which is food the guest did not order at a price they did not agree to. And
 * the price is the menu's to state, never the request's.
 */
class CafeMenuCartLineContributorPortionTest {

  private static final String SHOP_ID = "shop-1";
  private static final String ITEM_ID = "item-bc";

  private ShopMenuLookup shopMenuLookup;
  private CafeMenuCartLineContributor contributor;

  @BeforeEach
  void setUp() {
    shopMenuLookup = mock(ShopMenuLookup.class);
    contributor =
        new CafeMenuCartLineContributor(
            shopMenuLookup,
            mock(InventoryCartLookup.class),
            mock(CafeInventoryExtensionRepository.class));
  }

  @Test
  void aPortionIsPricedFromItsOwnRateAndNamedForTheGuest() {
    menu(butterChicken());

    CartLineSnapshot line = build("menu:" + ITEM_ID + "@half", 1);

    assertEquals(new BigDecimal("180"), line.getPriceToRetail());
    assertEquals(
        "Butter Chicken (Half)",
        line.getName(),
        "snapshotted, so the kitchen ticket reads it with no template change");
    assertEquals(0, new BigDecimal("180").compareTo(line.getTotalAmount()));
  }

  @Test
  void aPortionedItemWithNoPortionNamedIsRefused() {
    menu(butterChicken());

    ValidationException refused =
        assertThrows(ValidationException.class, () -> build("menu:" + ITEM_ID, 1));
    assertEquals("Choose a portion for Butter Chicken", refused.getMessage());
  }

  @Test
  void anUnknownPortionIsRefusedRatherThanFallingBackToTheFirstRate() {
    menu(butterChicken());

    ValidationException refused =
        assertThrows(ValidationException.class, () -> build("menu:" + ITEM_ID + "@qtr", 1));
    assertTrue(refused.getMessage().contains("qtr"), refused.getMessage());
    assertTrue(refused.getMessage().contains("Butter Chicken"), refused.getMessage());
  }

  @Test
  void aPortionNamedOnAnItemThatHasNoneIsRefusedToo() {
    // Every portion was removed while this screen was open. Pricing from the single price under
    // a portion name the menu no longer has is the same silent mis-sale.
    MenuItem chai = new MenuItem();
    chai.setId(ITEM_ID);
    chai.setName("Chai");
    chai.setSellingPrice(new BigDecimal("20"));
    chai.setAvailable(true);
    menu(chai);

    assertThrows(ValidationException.class, () -> build("menu:" + ITEM_ID + "@half", 1));
  }

  @Test
  void anItemWithNoPortionsSellsExactlyAsItAlwaysHas() {
    MenuItem chai = new MenuItem();
    chai.setId(ITEM_ID);
    chai.setName("Chai");
    chai.setSellingPrice(new BigDecimal("20"));
    chai.setAvailable(true);
    menu(chai);

    CartLineSnapshot line = build("menu:" + ITEM_ID, 2);

    assertEquals("Chai", line.getName());
    assertEquals(new BigDecimal("20"), line.getPriceToRetail());
    assertEquals("menu:" + ITEM_ID, line.getSellableRef());
  }

  @Test
  void theRequestCannotNameThePrice() {
    // A tampered request asking for the Full portion while supplying the Qtr's money. Testing
    // that the honest path prices correctly proves nothing about this one.
    menu(butterChicken());

    CartLineInput tampered =
        CartLineInput.builder()
            .sellableRef("menu:" + ITEM_ID + "@full")
            .quantity(1)
            .priceToRetail(new BigDecimal("90"))
            .build();

    CartLineSnapshot line =
        contributor
            .buildLines(List.of(tampered), CartBuildContext.builder().shopId(SHOP_ID).build())
            .get(0);

    assertEquals(
        new BigDecimal("320"),
        line.getPriceToRetail(),
        "the Full price from the menu, not the Qtr price from the request");
  }

  @Test
  void halfAndFullOfOneDishAreTwoCartLines() {
    menu(butterChicken());

    List<CartLineSnapshot> lines =
        contributor.buildLines(
            List.of(
                CartLineInput.builder().sellableRef("menu:" + ITEM_ID + "@half").quantity(1).build(),
                CartLineInput.builder().sellableRef("menu:" + ITEM_ID + "@full").quantity(1).build()),
            CartBuildContext.builder().shopId(SHOP_ID).build());

    assertEquals(2, lines.size());
    assertNotEquals(
        contributor.lineKey(lines.get(0)),
        contributor.lineKey(lines.get(1)),
        "the line key is the ref, so the portions never merge into one mispriced line");
    assertEquals("menu:" + ITEM_ID + "@half", contributor.lineKey(lines.get(0)));
  }

  // ------------------------------------------------- history against a mutable menu

  @Test
  void aSoldPortionKeepsItsNameAndPriceThroughEveryLaterMenuEdit() {
    MenuItem item = butterChicken();
    menu(item);

    CartLineSnapshot sold = build("menu:" + ITEM_ID + "@half", 1);

    // The shop renames the portion and then reprices it.
    item.getRates().get(0).setName("1/2 Plate");
    item.getRates().get(0).setPrice(new BigDecimal("210"));

    assertEquals("Butter Chicken (Half)", sold.getName(), "the sold line is a snapshot");
    assertEquals(new BigDecimal("180"), sold.getPriceToRetail());
    assertEquals(
        "menu:" + ITEM_ID + "@half",
        sold.getSellableRef(),
        "and the ref still resolves, because the id was never the name");

    // A new sale takes the new name and the new price, under the same frozen id.
    CartLineSnapshot resold = build("menu:" + ITEM_ID + "@half", 1);
    assertEquals("Butter Chicken (1/2 Plate)", resold.getName());
    assertEquals(new BigDecimal("210"), resold.getPriceToRetail());
  }

  @Test
  void aDeletedPortionLeavesHistoryReadableAndRefusesToBeSoldAgain() {
    MenuItem item = butterChicken();
    menu(item);

    CartLineSnapshot sold = build("menu:" + ITEM_ID + "@half", 1);

    item.getRates().removeIf(rate -> "half".equals(rate.getId()));

    assertEquals("Butter Chicken (Half)", sold.getName(), "the old bill still reads correctly");
    assertEquals(new BigDecimal("180"), sold.getPriceToRetail());
    assertThrows(ValidationException.class, () -> build("menu:" + ITEM_ID + "@half", 1));
  }

  @Test
  void aDeletedPortionCanStillBeTakenBackOffTheBill() {
    // The takeback names a line the cashier can see on the bill. Refusing it because the menu
    // moved on would leave them unable to remove it -- and the line's negative quantity is what
    // the next punch turns into the kitchen's CANCEL.
    MenuItem item = butterChicken();
    menu(item);
    item.getRates().removeIf(rate -> "half".equals(rate.getId()));

    CartLineSnapshot line = build("menu:" + ITEM_ID + "@half", -1);

    assertEquals(-1, line.getBaseQuantity());
    assertEquals("menu:" + ITEM_ID + "@half", line.getSellableRef());
    assertEquals("Butter Chicken", line.getName(), "the portion is gone; the dish still names it");
  }

  @Test
  void aTakebackOfALivePortionKeepsThePortionInItsName() {
    menu(butterChicken());

    assertEquals("Butter Chicken (Half)", build("menu:" + ITEM_ID + "@half", -1).getName());
  }

  // --------------------------------------------------------- malformed refs

  @Test
  void aMalformedRefIsRefusedAtTheCartBoundaryRatherThanCrashing() {
    menu(butterChicken());

    assertThrows(ValidationException.class, () -> build("menu:" + ITEM_ID + "@a@b", 1));
    assertThrows(ValidationException.class, () -> build("menu:" + ITEM_ID + "@", 1));
    assertThrows(ValidationException.class, () -> build("menu:@half", 1));
  }

  @Test
  void validationOfTheRequestRefusesAMalformedRefTheSameWay() {
    assertThrows(
        ValidationException.class,
        () ->
            contributor.validateRequestItems(
                List.of(
                    CartLineInput.builder()
                        .sellableRef("menu:" + ITEM_ID + "@a@b")
                        .quantity(1)
                        .build())));
  }

  // ------------------------------------------------------------------- helpers

  private void menu(MenuItem item) {
    when(shopMenuLookup.findMenuItem(SHOP_ID, ITEM_ID)).thenReturn(Optional.of(item));
  }

  private CartLineSnapshot build(String ref, int qty) {
    return contributor
        .buildLines(
            List.of(CartLineInput.builder().sellableRef(ref).quantity(qty).build()),
            CartBuildContext.builder().shopId(SHOP_ID).build())
        .get(0);
  }

  private static MenuItem butterChicken() {
    MenuItem item = new MenuItem();
    item.setId(ITEM_ID);
    item.setName("Butter Chicken");
    item.setAvailable(true);
    item.setRates(new ArrayList<>(List.of(rate("half", "Half", "180"), rate("full", "Full", "320"))));
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
