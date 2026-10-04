package com.inventory.product.cardlayout;

import com.inventory.pluginengine.cards.CardField;
import com.inventory.pluginengine.cards.CardLayout;
import com.inventory.pluginengine.cards.CardRow;
import com.inventory.pluginengine.cards.CardSection;
import com.inventory.pluginengine.cards.CardSurfaceDefinition;
import com.inventory.product.labels.FieldCatalog;
import com.inventory.product.labels.FieldUsage;
import com.inventory.product.labels.PrintableField;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Applies a stored layout against the current Field_Catalog (configurable-product-card Req 5.1–5.3).
 *
 * <p>Pure and total: it never throws for layout content. A field whose key the catalog no longer
 * knows, that is not usable on cards, or that the surface excludes is dropped; rows and sections
 * left empty are dropped; everything else keeps its relative order. Fields that survive are enriched
 * with their catalog label (unless overridden), value type, source group, item path, schema api key
 * and sensitivity. Resolving twice is a no-op on the second pass.
 */
@Component
public class CardLayoutResolver {

  public ResolvedCardLayout resolve(CardLayout layout, CardSurfaceDefinition surface, FieldCatalog catalog) {
    if (layout == null) {
      return new ResolvedCardLayout(List.of(), null);
    }
    List<ResolvedCardLayout.Section> sections = new ArrayList<>();
    for (CardSection section : layout.sections()) {
      List<ResolvedCardLayout.Row> rows = new ArrayList<>();
      for (CardRow row : section.rows()) {
        List<ResolvedCardLayout.Field> fields = new ArrayList<>();
        for (CardField field : row.fields()) {
          eligible(field, surface, catalog).map(cf -> enrich(field, cf)).ifPresent(fields::add);
        }
        if (!fields.isEmpty()) {
          rows.add(new ResolvedCardLayout.Row(fields));
        }
      }
      if (!rows.isEmpty()) {
        sections.add(
            new ResolvedCardLayout.Section(section.id(), section.title(), section.dividerAbove(), rows));
      }
    }
    return new ResolvedCardLayout(sections, layout.options());
  }

  /** The catalog field behind a card field when it may be shown on this surface. */
  static Optional<PrintableField> eligible(
      CardField field, CardSurfaceDefinition surface, FieldCatalog catalog) {
    if (field == null || field.fieldKey() == null || catalog == null) {
      return Optional.empty();
    }
    if (surface != null && surface.excludes(field.fieldKey())) {
      return Optional.empty();
    }
    return catalog.findForUsage(field.fieldKey(), FieldUsage.CARD);
  }

  static ResolvedCardLayout.Field enrich(CardField field, PrintableField catalogField) {
    String label = field.labelOverride() != null ? field.labelOverride() : catalogField.label();
    return new ResolvedCardLayout.Field(
        field.fieldKey(),
        label,
        field.showLabel(),
        field.emphasis(),
        catalogField.valueType(),
        catalogField.sourceGroup(),
        catalogField.itemPath(),
        catalogField.schemaApiKey(),
        catalogField.sensitivity());
  }
}
