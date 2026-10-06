package com.inventory.product.labels;

// Feature: configurable-product-card, Property 1: Catalog usage partition

import static org.assertj.core.api.Assertions.assertThat;

import com.inventory.product.domain.model.Shop;
import com.inventory.product.labels.LabelFieldCatalogServiceProperties.Scenario;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property 1: Catalog usage partition.
 *
 * <p><b>Validates: Requirements 1.1, 1.2, 1.3, 1.5, 1.6</b>
 *
 * <p>For any shop and vertical schema: every catalog field has at least one usage and a
 * sensitivity; every {@code CARD} field has an item path; the {@code LABEL} view equals the
 * pre-card catalog (static sticker sets, named rates, vertical, shop — in that order, with the
 * card-only keys absent); the {@code CARD} view contains every card-only key and no shop key; and
 * both views keep the relative order of the full catalog.
 */
class FieldCatalogUsageProperties {

  @Property(tries = 100)
  void usagesPartitionTheCatalog(@ForAll("scenarios") Scenario scenario) {
    LabelFieldCatalogService service = LabelFieldCatalogServiceProperties.service(scenario);
    Shop shop = LabelFieldCatalogServiceProperties.shop(scenario, scenario.shopType());

    FieldCatalog catalog = service.catalog(shop);
    List<PrintableField> all = catalog.fields();
    List<PrintableField> labelView = catalog.forUsage(FieldUsage.LABEL);
    List<PrintableField> cardView = catalog.forUsage(FieldUsage.CARD);

    // Req 1.1: every field is classified.
    for (PrintableField f : all) {
      assertThat(f.usages()).as("usages of %s", f.fieldKey()).isNotEmpty();
      assertThat(f.sensitivity()).as("sensitivity of %s", f.fieldKey()).isNotNull();
      if (f.usableFor(FieldUsage.CARD)) {
        assertThat(f.itemPath()).as("itemPath of card field %s", f.fieldKey()).isNotBlank();
      }
    }

    // Every field is in at least one view; views are order-preserving sub-lists of the catalog.
    Set<String> union = new HashSet<>();
    labelView.forEach(f -> union.add(f.fieldKey()));
    cardView.forEach(f -> union.add(f.fieldKey()));
    catalog.forUsage(FieldUsage.SEARCH).forEach(f -> union.add(f.fieldKey()));
    assertThat(union).hasSameSizeAs(all);
    assertSubsequence(labelView, all);
    assertSubsequence(cardView, all);

    // Req 1.6: the LABEL view is exactly the pre-card sticker catalog.
    List<String> labelKeys = labelView.stream().map(PrintableField::fieldKey).toList();
    Set<String> cardOnlyKeys = cardOnlyKeys();
    assertThat(labelKeys).doesNotContainAnyElementsOf(cardOnlyKeys);
    List<String> expectedStaticLabelKeys = new ArrayList<>();
    LabelLayoutDefaults.coreFields().forEach(f -> expectedStaticLabelKeys.add(f.fieldKey()));
    LabelLayoutDefaults.pricingFields().forEach(f -> expectedStaticLabelKeys.add(f.fieldKey()));
    assertThat(labelKeys).startsWith(expectedStaticLabelKeys.toArray(String[]::new));
    List<String> shopKeys = LabelLayoutDefaults.shopFields().stream().map(PrintableField::fieldKey).toList();
    assertThat(labelKeys).endsWith(shopKeys.toArray(String[]::new));

    // Req 1.2, 1.3, 1.5: the CARD view has every card-only key and no shop field.
    List<String> cardKeys = cardView.stream().map(PrintableField::fieldKey).toList();
    assertThat(cardKeys).containsAll(cardOnlyKeys);
    assertThat(cardKeys).doesNotContainAnyElementsOf(shopKeys);
    for (PrintableField f : cardView) {
      assertThat(f.sourceGroup()).isNotEqualTo(SourceGroup.SHOP);
      if (f.sourceGroup() == SourceGroup.VERTICAL) {
        assertThat(f.itemPath())
            .isEqualTo(LabelFieldCatalogService.VERTICAL_ITEM_PATH_PREFIX + f.schemaApiKey());
      }
    }

    // findForUsage agrees with forUsage.
    for (PrintableField f : all) {
      assertThat(catalog.findForUsage(f.fieldKey(), FieldUsage.LABEL).isPresent())
          .isEqualTo(f.usableFor(FieldUsage.LABEL));
      assertThat(catalog.findForUsage(f.fieldKey(), FieldUsage.CARD).isPresent())
          .isEqualTo(f.usableFor(FieldUsage.CARD));
    }
  }

  @Provide
  Arbitrary<Scenario> scenarios() {
    return new LabelFieldCatalogServiceProperties().scenarios();
  }

  private static Set<String> cardOnlyKeys() {
    Set<String> keys = new HashSet<>();
    LabelLayoutDefaults.cardProductFields().forEach(f -> keys.add(f.fieldKey()));
    LabelLayoutDefaults.cardPricingFields().forEach(f -> keys.add(f.fieldKey()));
    LabelLayoutDefaults.cardLotFields().forEach(f -> keys.add(f.fieldKey()));
    return keys;
  }

  /** Asserts {@code sub} is {@code full} with some elements removed, order kept. */
  private static void assertSubsequence(List<PrintableField> sub, List<PrintableField> full) {
    int i = 0;
    for (PrintableField f : full) {
      if (i < sub.size() && sub.get(i).fieldKey().equals(f.fieldKey())) {
        i++;
      }
    }
    assertThat(i).as("view is an ordered sub-list of the catalog").isEqualTo(sub.size());
  }
}
