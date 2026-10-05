package com.inventory.product.cardlayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.inventory.pluginengine.cards.CardField;
import com.inventory.pluginengine.cards.CardLayout;
import com.inventory.pluginengine.cards.CardRow;
import com.inventory.pluginengine.cards.CardSection;
import com.inventory.pluginengine.cards.CardSurfaceDefinition;
import com.inventory.pluginengine.cards.CardVariant;
import com.inventory.pluginengine.cards.Emphasis;
import com.inventory.product.labels.FieldCatalog;
import com.inventory.product.rest.dto.response.SurfaceLayoutResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Configurable-product-card Req 6.1, 6.2 literal defaults, and the wire shape (Req 4.1, 5.2). */
class CardLayoutDefaultsTest {

  @Test
  void productSearchDefaultReproducesTodaysCard() {
    assertEquals(
        List.of(
            "[companyName]",
            "[batchNo=Batch]",
            "[barcodeText=Barcode]",
            "[location]",
            "|[availableCount]",
            "|[receivedCount, soldCount]",
            "[sellingPrice=Selling Price*]",
            "[mrp*]",
            "[saleAdditionalDiscount=Additional Discount*]",
            "[expiryDate=Expires]",
            "[purchaseDate=Purchased]"),
        describe(CardLayoutDefaults.productSearch()));
    assertTrue(CardLayoutDefaults.productSearch().options().showAttributeChips());
    assertTrue(CardLayoutDefaults.productSearch().options().showDescription());
  }

  @Test
  void scanSellDefaultReproducesTodaysDropdownRow() {
    assertEquals(
        List.of(
            "[companyName]",
            "[batchNo=Batch]",
            "[barcodeText=Barcode]",
            "[availableCount]",
            "[mrp*]",
            "[sellingPrice=Selling*]",
            "[expiryDate=Expires*]"),
        describe(CardLayoutDefaults.scanSell()));
    assertFalse(CardLayoutDefaults.scanSell().options().showAttributeChips());
    assertFalse(CardLayoutDefaults.scanSell().options().showDescription());
  }

  @Test
  void coreSurfacesAreProductSearchThenScanSellBothBillingModeAware() {
    List<CardSurfaceDefinition> core = CardLayoutDefaults.coreSurfaces();
    assertEquals(List.of("product-search", "scan-sell"), core.stream().map(CardSurfaceDefinition::surfaceId).toList());
    core.forEach(s -> assertTrue(s.billingModeAware()));
    assertTrue(core.get(1).excludes("description"));
    assertFalse(core.get(0).excludes("description"));
  }

  @Test
  void defaultsResolveWithoutLoss() {
    FieldCatalog catalog = CardLayoutTestFixtures.staticCatalog();
    CardLayoutResolver resolver = new CardLayoutResolver();
    for (CardSurfaceDefinition s : CardLayoutDefaults.coreSurfaces()) {
      CardLayout layout = s.coreDefault(CardVariant.REGULAR);
      ResolvedCardLayout resolved = resolver.resolve(layout, s, catalog);
      assertEquals(layout.fieldCount(), resolved.fieldCount(), s.surfaceId() + " default must resolve in full");
    }
  }

  @Test
  void wireShapeUsesUppercaseEnumsAndVariantKeys() throws Exception {
    ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    FieldCatalog catalog = CardLayoutTestFixtures.staticCatalog();
    CardSurfaceDefinition surface = CardLayoutDefaults.coreSurfaces().get(0);
    ResolvedCardLayout resolved = new CardLayoutResolver().resolve(CardLayoutDefaults.productSearch(), surface, catalog);
    SurfaceLayoutResponse response =
        SurfaceLayoutResponse.of(surface, Map.of(CardVariant.REGULAR, resolved), false, Instant.EPOCH, "u1");

    String json = mapper.writeValueAsString(response);

    assertTrue(json.contains("\"variants\":{\"REGULAR\":{"), json);
    assertTrue(json.contains("\"emphasis\":\"STRONG\""), json);
    assertTrue(json.contains("\"sensitivity\":\"PUBLIC\""), json);
    assertTrue(json.contains("\"valueType\":\"currency\""), json);
    assertTrue(json.contains("\"sourceGroup\":\"pricing\""), json);
    assertTrue(json.contains("\"itemPath\":\"maximumRetailPrice\""), json);
    assertTrue(json.contains("\"blankValueBehavior\":\"HIDE_LINE\""), json);
    assertTrue(json.contains("\"isDefault\":false"), json);
  }

  private static List<String> describe(CardLayout layout) {
    List<String> rows = new ArrayList<>();
    for (CardSection section : layout.sections()) {
      for (CardRow row : section.rows()) {
        rows.add((section.dividerAbove() ? "|" : "") + row.fields().stream().map(CardLayoutDefaultsTest::describe).toList());
      }
    }
    return rows;
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
