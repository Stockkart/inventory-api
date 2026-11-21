package com.inventory.product.cardlayout;

import com.inventory.pluginengine.cards.CardBlankValueBehavior;
import com.inventory.pluginengine.cards.CardField;
import com.inventory.pluginengine.cards.CardLayout;
import com.inventory.pluginengine.cards.CardOptions;
import com.inventory.pluginengine.cards.CardRow;
import com.inventory.pluginengine.cards.CardSection;
import com.inventory.pluginengine.cards.CardSurfaceDefinition;
import com.inventory.pluginengine.cards.CardVariant;
import com.inventory.pluginengine.cards.Emphasis;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.labels.FieldCatalog;
import com.inventory.product.labels.FieldUsage;
import com.inventory.product.labels.LabelLayoutDefaults;
import com.inventory.product.labels.PrintableField;
import com.inventory.product.labels.Sensitivity;
import com.inventory.product.labels.SourceGroup;
import com.inventory.product.labels.ValueType;
import com.inventory.product.rest.dto.request.LenientBoolean;
import com.inventory.product.rest.dto.request.SaveCardLayoutRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

/** Shared generators and converters for the card layout property tests. */
final class CardLayoutTestFixtures {

  private CardLayoutTestFixtures() {}

  // ---- catalogs ------------------------------------------------------------------------------

  /** The static catalog (no vertical, no rates) as the service would build it for a retailer. */
  static FieldCatalog staticCatalog() {
    List<PrintableField> fields = new ArrayList<>();
    fields.addAll(LabelLayoutDefaults.coreFields());
    fields.addAll(LabelLayoutDefaults.cardProductFields());
    fields.addAll(LabelLayoutDefaults.pricingFields());
    fields.addAll(LabelLayoutDefaults.cardPricingFields());
    fields.addAll(LabelLayoutDefaults.lotFields());
    fields.addAll(LabelLayoutDefaults.cardLotFields());
    fields.add(
        new PrintableField(
            "vertical.brand",
            "Brand",
            SourceGroup.VERTICAL,
            ValueType.TEXT,
            Set.of(ShopType.values()),
            "brandName",
            PrintableField.DEFAULT_USAGES,
            Sensitivity.PUBLIC,
            "verticalFields.brandName"));
    fields.addAll(LabelLayoutDefaults.shopFields());
    return new FieldCatalog(
        fields, LabelLayoutDefaults.STICKER_SIZES, ShopType.RETAILER, true, null, null);
  }

  static List<String> cardKeys(FieldCatalog catalog) {
    return catalog.forUsage(FieldUsage.CARD).stream().map(PrintableField::fieldKey).toList();
  }

  static List<String> labelOnlyKeys(FieldCatalog catalog) {
    return catalog.fields().stream()
        .filter(f -> !f.usableFor(FieldUsage.CARD))
        .map(PrintableField::fieldKey)
        .toList();
  }

  // ---- surfaces ------------------------------------------------------------------------------

  static CardSurfaceDefinition surface(String id, boolean aware, Set<String> excluded) {
    return new CardSurfaceDefinition(
        id, id, aware, excluded, Map.of(CardVariant.REGULAR, CardLayoutDefaults.productSearch()));
  }

  static Arbitrary<CardSurfaceDefinition> surfaces(FieldCatalog catalog) {
    Arbitrary<Set<String>> excluded =
        Arbitraries.of(cardKeys(catalog)).set().ofMaxSize(3);
    return Combinators.combine(Arbitraries.of(true, false), excluded)
        .as((aware, ex) -> surface("surf-" + (aware ? "aware" : "single"), aware, ex));
  }

  // ---- layouts (model) -----------------------------------------------------------------------

  /** Random layouts drawing keys from the card view plus label-only and unknown noise keys. */
  static Arbitrary<CardLayout> layouts(FieldCatalog catalog, boolean withNoise) {
    List<String> pool = new ArrayList<>(cardKeys(catalog));
    if (withNoise) {
      pool.addAll(labelOnlyKeys(catalog));
      pool.add("nope");
      pool.add("vertical.gone");
    }
    Arbitrary<CardField> field =
        Combinators.combine(
                Arbitraries.of(pool),
                Arbitraries.of(true, false),
                Arbitraries.oneOf(Arbitraries.just(null), Arbitraries.strings().alpha().ofMaxLength(12)),
                Arbitraries.of(Emphasis.class))
            .as(CardField::new);
    Arbitrary<CardRow> row = field.list().ofMinSize(1).ofMaxSize(3).map(CardRow::new);
    Arbitrary<CardSection> section =
        Combinators.combine(
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8),
                Arbitraries.oneOf(Arbitraries.just(null), Arbitraries.strings().ofMaxLength(20)),
                Arbitraries.of(true, false),
                row.list().ofMinSize(1).ofMaxSize(4))
            .as(CardSection::new);
    Arbitrary<CardOptions> options =
        Combinators.combine(
                Arbitraries.of(CardBlankValueBehavior.class),
                Arbitraries.of(true, false),
                Arbitraries.of(true, false))
            .as(CardOptions::new);
    return Combinators.combine(section.list().ofMaxSize(4), options).as(CardLayout::new);
  }

  // ---- model → request spec ------------------------------------------------------------------

  static SaveCardLayoutRequest request(Map<CardVariant, CardLayout> variants) {
    Map<String, SaveCardLayoutRequest.LayoutSpec> specs = new LinkedHashMap<>();
    variants.forEach((v, l) -> specs.put(v.name(), spec(l)));
    return new SaveCardLayoutRequest(specs);
  }

  static SaveCardLayoutRequest.LayoutSpec spec(CardLayout layout) {
    List<SaveCardLayoutRequest.SectionSpec> sections = new ArrayList<>();
    for (CardSection s : layout.sections()) {
      List<SaveCardLayoutRequest.RowSpec> rows = new ArrayList<>();
      for (CardRow r : s.rows()) {
        List<SaveCardLayoutRequest.FieldSpec> fields = new ArrayList<>();
        for (CardField f : r.fields()) {
          fields.add(
              new SaveCardLayoutRequest.FieldSpec(
                  f.fieldKey(), LenientBoolean.of(f.showLabel()), f.labelOverride(), f.emphasis().name()));
        }
        rows.add(new SaveCardLayoutRequest.RowSpec(fields));
      }
      sections.add(
          new SaveCardLayoutRequest.SectionSpec(s.id(), s.title(), LenientBoolean.of(s.dividerAbove()), rows));
    }
    CardOptions o = layout.options();
    return new SaveCardLayoutRequest.LayoutSpec(
        sections,
        new SaveCardLayoutRequest.OptionsSpec(
            o.blankValueBehavior().name(),
            LenientBoolean.of(o.showAttributeChips()),
            LenientBoolean.of(o.showDescription())));
  }

  /** Flattened field keys in display order. */
  static List<String> keys(CardLayout layout) {
    List<String> keys = new ArrayList<>();
    layout.sections().forEach(s -> s.rows().forEach(r -> r.fields().forEach(f -> keys.add(f.fieldKey()))));
    return keys;
  }

  static List<String> keys(ResolvedCardLayout layout) {
    List<String> keys = new ArrayList<>();
    layout.sections().forEach(s -> s.rows().forEach(r -> r.fields().forEach(f -> keys.add(f.fieldKey()))));
    return keys;
  }
}
