package com.inventory.product.cardlayout;

import com.inventory.pluginengine.cards.CardBlankValueBehavior;
import com.inventory.pluginengine.cards.CardField;
import com.inventory.pluginengine.cards.CardLayout;
import com.inventory.pluginengine.cards.CardOptions;
import com.inventory.pluginengine.cards.CardRow;
import com.inventory.pluginengine.cards.CardSection;
import com.inventory.pluginengine.cards.CardVariant;
import com.inventory.pluginengine.cards.Emphasis;
import com.inventory.product.domain.model.CardLayoutDocument;
import com.inventory.product.domain.model.CardLayoutDocument.FieldDoc;
import com.inventory.product.domain.model.CardLayoutDocument.LayoutDoc;
import com.inventory.product.domain.model.CardLayoutDocument.OptionsDoc;
import com.inventory.product.domain.model.CardLayoutDocument.RowDoc;
import com.inventory.product.domain.model.CardLayoutDocument.SectionDoc;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts between the immutable domain records and the Mongo document shape.
 *
 * <p>Reads are lenient (configurable-product-card Req 5.3): null lists become empty, unknown enum
 * strings fall back to defaults, null {@code showLabel} is {@code true}, unknown variant keys are
 * ignored. A document written by an older or newer build therefore always yields a usable layout.
 */
public final class CardLayoutDocuments {

  private CardLayoutDocuments() {}

  // ---- document → domain ---------------------------------------------------------------------

  /** Variants present in the document, by parsed variant; unknown keys skipped. */
  public static Map<CardVariant, CardLayout> toLayouts(CardLayoutDocument doc) {
    Map<CardVariant, CardLayout> result = new EnumMap<>(CardVariant.class);
    if (doc == null || doc.getVariants() == null) {
      return result;
    }
    doc.getVariants()
        .forEach(
            (key, layout) ->
                CardVariant.parse(key).ifPresent(v -> result.put(v, toLayout(layout))));
    return result;
  }

  public static CardLayout toLayout(LayoutDoc doc) {
    if (doc == null) {
      return CardLayout.EMPTY;
    }
    List<CardSection> sections = new ArrayList<>();
    for (SectionDoc s : nullSafe(doc.getSections())) {
      if (s == null) {
        continue;
      }
      List<CardRow> rows = new ArrayList<>();
      for (RowDoc r : nullSafe(s.getRows())) {
        if (r == null) {
          continue;
        }
        List<CardField> fields = new ArrayList<>();
        for (FieldDoc f : nullSafe(r.getFields())) {
          if (f == null || f.getFieldKey() == null) {
            continue;
          }
          fields.add(
              new CardField(
                  f.getFieldKey(),
                  f.getShowLabel() == null || f.getShowLabel(),
                  f.getLabelOverride(),
                  Emphasis.parse(f.getEmphasis()).orElse(Emphasis.NORMAL)));
        }
        rows.add(new CardRow(fields));
      }
      sections.add(
          new CardSection(
              s.getId(), s.getTitle(), Boolean.TRUE.equals(s.getDividerAbove()), rows));
    }
    return new CardLayout(sections, toOptions(doc.getOptions()));
  }

  static CardOptions toOptions(OptionsDoc doc) {
    CardOptions defaults = CardLayoutDefaults.DEFAULT_OPTIONS;
    if (doc == null) {
      return defaults;
    }
    return new CardOptions(
        CardBlankValueBehavior.parse(doc.getBlankValueBehavior()).orElse(defaults.blankValueBehavior()),
        doc.getShowAttributeChips() == null ? defaults.showAttributeChips() : doc.getShowAttributeChips(),
        doc.getShowDescription() == null ? defaults.showDescription() : doc.getShowDescription());
  }

  // ---- domain → document ---------------------------------------------------------------------

  public static Map<String, LayoutDoc> fromLayouts(Map<CardVariant, CardLayout> layouts) {
    Map<String, LayoutDoc> result = new LinkedHashMap<>();
    layouts.forEach((v, l) -> result.put(v.name(), fromLayout(l)));
    return result;
  }

  public static LayoutDoc fromLayout(CardLayout layout) {
    List<SectionDoc> sections = new ArrayList<>();
    for (CardSection s : layout.sections()) {
      List<RowDoc> rows = new ArrayList<>();
      for (CardRow r : s.rows()) {
        List<FieldDoc> fields = new ArrayList<>();
        for (CardField f : r.fields()) {
          fields.add(new FieldDoc(f.fieldKey(), f.showLabel(), f.labelOverride(), f.emphasis().name()));
        }
        rows.add(new RowDoc(fields));
      }
      sections.add(new SectionDoc(s.id(), s.title(), s.dividerAbove(), rows));
    }
    CardOptions o = layout.options();
    return new LayoutDoc(
        sections,
        new OptionsDoc(o.blankValueBehavior().name(), o.showAttributeChips(), o.showDescription()));
  }

  private static <T> List<T> nullSafe(List<T> list) {
    return list == null ? List.of() : list;
  }
}
