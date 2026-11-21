package com.inventory.product.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.product.domain.model.enums.ShopType;
import java.util.List;
import java.util.Set;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

/**
 * Smoke coverage for the labels model skeleton. Mixes a JUnit 5 test with a jqwik property in one
 * class to confirm Surefire discovers both engines on the JUnit Platform.
 */
class LabelModelSmokeTest {

  private static final PrintableField NAME =
      new PrintableField(
          "productName", "Product name", SourceGroup.PRODUCT, ValueType.TEXT, Set.of(ShopType.values()));

  @Test
  void catalogFindReturnsFieldByKeyAndDefaultsShopType() {
    FieldCatalog catalog =
        new FieldCatalog(List.of(NAME), List.of(new StickerSizeSpec("50x25", 50, 25, 3)), null, true, null, null);

    assertEquals(NAME, catalog.find("productName").orElseThrow());
    assertTrue(catalog.find("missing").isEmpty());
    assertTrue(catalog.find(null).isEmpty());
    assertEquals(ShopType.RETAILER, catalog.effectiveShopType());
    assertEquals(3, catalog.findStickerSize("50x25").orElseThrow().maxLines());
  }

  @Test
  void enumsUseLowercaseWireNames() {
    assertEquals("product", SourceGroup.PRODUCT.wireName());
    assertEquals("vertical", SourceGroup.VERTICAL.wireName());
    assertEquals("percentage", ValueType.PERCENTAGE.wireName());
    assertEquals("text", EnabledFieldDto.from(NAME).valueType());
    assertFalse(NAME.isAvailableFor(null));
  }

  @Property(tries = 20)
  void effectiveLayoutKeysFollowEnabledFieldOrder(@ForAll List<String> keys) {
    List<EnabledFieldDto> fields = keys.stream().map(k -> new EnabledFieldDto(k, k, "text")).toList();
    EffectiveLayout layout =
        new EffectiveLayout(
            fields, "50x25", new StickerSizeSpec("50x25", 50, 25, 3), true, false, BlankValueBehavior.HIDE_LINE);

    assertEquals(keys, layout.enabledFieldKeys());
  }
}
