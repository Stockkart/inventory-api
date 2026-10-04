package com.inventory.plugins.cafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.pluginengine.cards.CardField;
import com.inventory.pluginengine.cards.CardLayout;
import com.inventory.pluginengine.cards.CardRow;
import com.inventory.pluginengine.cards.CardSection;
import com.inventory.pluginengine.cards.CardSurfaceDefinition;
import com.inventory.pluginengine.cards.CardVariant;
import com.inventory.pluginengine.cards.Emphasis;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Configurable-product-card Req 2.3 and 6.3: the cafe adds exactly one single-variant surface. */
class CafeCardSurfaceContributorTest {

  private final CafeCardSurfaceContributor contributor = new CafeCardSurfaceContributor();

  @Test
  void contributesExactlyTheIngredientSearchSurface() {
    List<CardSurfaceDefinition> surfaces = contributor.surfaces();

    assertEquals(1, surfaces.size());
    CardSurfaceDefinition def = surfaces.get(0);
    assertEquals("cafe-ingredient-search", def.surfaceId());
    assertEquals("Ingredient search", def.label());
    assertFalse(def.billingModeAware());
    assertTrue(def.excludedFieldKeys().isEmpty());
    assertTrue(def.supports(CardVariant.REGULAR));
    assertFalse(def.supports(CardVariant.BASIC));
    assertEquals(java.util.Set.of("product-search"), contributor.hiddenCoreSurfaceIds());
  }

  @Test
  void defaultLayoutReproducesTodaysIngredientCard() {
    CardLayout layout = contributor.surfaces().get(0).coreDefault(CardVariant.REGULAR);

    List<String> rows = new ArrayList<>();
    for (CardSection section : layout.sections()) {
      for (CardRow row : section.rows()) {
        rows.add(
            (section.dividerAbove() ? "|" : "")
                + row.fields().stream().map(CafeCardSurfaceContributorTest::describe).toList());
      }
    }
    assertEquals(
        List.of(
            "[companyName]",
            "[barcodeText]",
            "[location]",
            "|[currentCount=Current]",
            "|[receivedCount, soldCount=Used]",
            "|[thresholdCount=Threshold]",
            "[costPrice=Cost*]",
            "[sellingPrice=Selling Price*]",
            "[purchaseDate=Purchased]"),
        rows);
    assertTrue(layout.options().showAttributeChips());
    assertTrue(layout.options().showDescription());
  }

  @Test
  void overridesNothingForOtherSurfaces() {
    assertTrue(contributor.defaultLayout("product-search", CardVariant.REGULAR).isEmpty());
  }

  private static String describe(CardField f) {
    String s = f.fieldKey();
    if (f.labelOverride() != null) {
      s += "=" + f.labelOverride();
    }
    if (f.emphasis() == Emphasis.STRONG) {
      s += "*";
    }
    return s;
  }
}
