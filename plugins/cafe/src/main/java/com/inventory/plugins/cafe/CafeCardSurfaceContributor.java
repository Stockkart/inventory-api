package com.inventory.plugins.cafe;

import com.inventory.pluginengine.cards.CardField;
import com.inventory.pluginengine.cards.CardLayout;
import com.inventory.pluginengine.cards.CardOptions;
import com.inventory.pluginengine.cards.CardRow;
import com.inventory.pluginengine.cards.CardSection;
import com.inventory.pluginengine.cards.CardSurfaceContributor;
import com.inventory.pluginengine.cards.CardSurfaceDefinition;
import com.inventory.pluginengine.cards.CardVariant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cafe contribution to configurable product cards (configurable-product-card Req 2.3, 6.3).
 *
 * <p>Adds the {@code cafe-ingredient-search} surface used by the Manual stock page. Ingredients
 * have no billing mode, so the surface keeps a single layout. The default reproduces today's {@code
 * IngredientSearchCard} body: identity, divider, stock counts, cost and selling price in bold,
 * purchase date.
 */
public final class CafeCardSurfaceContributor implements CardSurfaceContributor {

  /** Stable surface id exchanged with the frontend. */
  public static final String INGREDIENT_SEARCH_SURFACE_ID = "cafe-ingredient-search";

  /** The core surface this vertical replaces (kept as a literal: pluginengine has no core ids). */
  static final String PRODUCT_SEARCH_SURFACE_ID = "product-search";

  private static final CardLayout INGREDIENT_DEFAULT =
      CardLayout.of(
          CardOptions.DEFAULT,
          CardSection.of(
              "identity",
              CardRow.single("companyName"),
              CardRow.single("barcodeText"),
              CardRow.single("location")),
          CardSection.divided(
              "stock",
              CardRow.of(CardField.labelled("currentCount", "Current")),
              CardRow.of(CardField.of("receivedCount"), CardField.labelled("soldCount", "Used")),
              CardRow.of(CardField.labelled("thresholdCount", "Threshold"))),
          CardSection.of(
              "pricing",
              CardRow.of(CardField.strong("costPrice", "Cost")),
              CardRow.of(CardField.strong("sellingPrice", "Selling Price"))),
          CardSection.of("dates", CardRow.of(CardField.labelled("purchaseDate", "Purchased"))));

  private static final CardSurfaceDefinition INGREDIENT_SEARCH =
      new CardSurfaceDefinition(
          INGREDIENT_SEARCH_SURFACE_ID,
          "Ingredient search",
          false,
          Set.of(),
          Map.of(CardVariant.REGULAR, INGREDIENT_DEFAULT));

  @Override
  public List<CardSurfaceDefinition> surfaces() {
    return List.of(INGREDIENT_SEARCH);
  }

  /** A cafe searches ingredients, not products: the core Product search card is not offered. */
  @Override
  public Set<String> hiddenCoreSurfaceIds() {
    return Set.of(PRODUCT_SEARCH_SURFACE_ID);
  }
}
