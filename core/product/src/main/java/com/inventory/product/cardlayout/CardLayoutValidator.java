package com.inventory.product.cardlayout;

import static com.inventory.product.cardlayout.CardLayoutDefaults.MAX_FIELDS_PER_ROW;
import static com.inventory.product.cardlayout.CardLayoutDefaults.MAX_FIELDS_TOTAL;
import static com.inventory.product.cardlayout.CardLayoutDefaults.MAX_ROWS_PER_SECTION;
import static com.inventory.product.cardlayout.CardLayoutDefaults.MAX_SECTIONS;
import static com.inventory.product.cardlayout.CardLayoutDefaults.MAX_TEXT_LENGTH;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.cards.CardBlankValueBehavior;
import com.inventory.pluginengine.cards.CardField;
import com.inventory.pluginengine.cards.CardLayout;
import com.inventory.pluginengine.cards.CardOptions;
import com.inventory.pluginengine.cards.CardRow;
import com.inventory.pluginengine.cards.CardSection;
import com.inventory.pluginengine.cards.CardSurfaceDefinition;
import com.inventory.pluginengine.cards.CardVariant;
import com.inventory.pluginengine.cards.Emphasis;
import com.inventory.product.labels.FieldCatalog;
import com.inventory.product.labels.FieldUsage;
import com.inventory.product.labels.PrintableField;
import com.inventory.product.rest.dto.request.LenientBoolean;
import com.inventory.product.rest.dto.request.SaveCardLayoutRequest;
import com.inventory.product.rest.dto.request.SaveCardLayoutRequest.FieldSpec;
import com.inventory.product.rest.dto.request.SaveCardLayoutRequest.LayoutSpec;
import com.inventory.product.rest.dto.request.SaveCardLayoutRequest.OptionsSpec;
import com.inventory.product.rest.dto.request.SaveCardLayoutRequest.RowSpec;
import com.inventory.product.rest.dto.request.SaveCardLayoutRequest.SectionSpec;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Turns a save request into validated, default-filled {@link CardLayout}s per variant, or throws one
 * {@link ValidationException} naming every violation (configurable-product-card Req 2.5, 2.6,
 * 3.3–3.7).
 *
 * <p>Pure: no I/O, no state. Messages are collected in check order and joined by {@code "; "} by the
 * exception so a client sees everything wrong with its request in one round trip.
 */
@Component
public class CardLayoutValidator {

  static final String EMPHASIS_ERROR = "emphasis must be NORMAL, STRONG or MUTED";
  static final String BLANK_BEHAVIOUR_ERROR = "blankValueBehavior must be HIDE_LINE or SHOW_DASH";

  /**
   * @param request the submitted body
   * @param surface the surface being saved
   * @param catalog the shop's Field_Catalog
   * @param defaults supplies the default layout for a variant the request omits
   * @return one filled layout per variant the surface supports
   * @throws ValidationException listing every violation
   */
  public Map<CardVariant, CardLayout> validate(
      SaveCardLayoutRequest request,
      CardSurfaceDefinition surface,
      FieldCatalog catalog,
      Function<CardVariant, CardLayout> defaults) {
    Set<String> errors = new LinkedHashSet<>();
    Map<CardVariant, CardLayout> result = new EnumMap<>(CardVariant.class);

    Map<String, LayoutSpec> variants = request == null ? Map.of() : request.variants();
    for (Map.Entry<String, LayoutSpec> entry : variants.entrySet()) {
      Optional<CardVariant> variant = CardVariant.parse(entry.getKey());
      if (variant.isEmpty()) {
        errors.add("Unknown variant: " + entry.getKey() + " (allowed: REGULAR, BASIC)");
        continue;
      }
      if (!surface.supports(variant.get())) {
        errors.add(
            "Surface " + surface.surfaceId() + " has a single layout; remove the BASIC variant");
        continue;
      }
      CardLayout layout = validateLayout(entry.getValue(), variant.get(), surface, catalog, errors);
      if (layout != null) {
        result.put(variant.get(), layout);
      }
    }

    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }

    // Req 3.7: fill omitted variants with defaults so a saved document is always complete.
    for (CardVariant v : surface.variants()) {
      result.computeIfAbsent(v, defaults);
    }
    return result;
  }

  // ---- one variant ---------------------------------------------------------------------------

  private CardLayout validateLayout(
      LayoutSpec spec,
      CardVariant variant,
      CardSurfaceDefinition surface,
      FieldCatalog catalog,
      Set<String> errors) {
    if (spec == null) {
      return null; // treated as omitted → default
    }
    String where = variant.name();
    int before = errors.size();

    List<SectionSpec> sectionSpecs = spec.sections();
    if (sectionSpecs.size() > MAX_SECTIONS) {
      errors.add(
          where + ": at most " + MAX_SECTIONS + " sections allowed; " + sectionSpecs.size() + " given");
    }

    Set<String> sectionIds = new HashSet<>();
    Set<String> seenKeys = new HashSet<>();
    Set<String> duplicates = new LinkedHashSet<>();
    Set<String> unknown = new LinkedHashSet<>();
    Set<String> notForCards = new LinkedHashSet<>();
    int total = 0;

    List<CardSection> sections = new ArrayList<>();
    for (int si = 0; si < sectionSpecs.size(); si++) {
      SectionSpec s = sectionSpecs.get(si);
      String sectionId = StringUtils.hasText(s.id()) ? s.id().trim() : null;
      if (sectionId == null || !sectionIds.add(sectionId)) {
        errors.add(where + ": section ids must be unique and non-blank");
        sectionId = sectionId == null ? "section-" + (si + 1) : sectionId;
      }
      String sectionName = sectionId;
      if (s.title() != null && s.title().length() > MAX_TEXT_LENGTH) {
        errors.add(where + ": section " + sectionName + " title exceeds " + MAX_TEXT_LENGTH + " characters");
      }
      if (LenientBoolean.isInvalid(s.dividerAbove())) {
        errors.add(where + ": section " + sectionName + " dividerAbove must be true or false");
      }
      if (s.rows().size() > MAX_ROWS_PER_SECTION) {
        errors.add(
            where
                + ": section "
                + sectionName
                + " has "
                + s.rows().size()
                + " rows (max "
                + MAX_ROWS_PER_SECTION
                + ")");
      }

      List<CardRow> rows = new ArrayList<>();
      for (int ri = 0; ri < s.rows().size(); ri++) {
        RowSpec r = s.rows().get(ri);
        String rowName = "Row " + (ri + 1) + " in section " + sectionName;
        if (r.fields().isEmpty()) {
          errors.add(where + ": " + rowName + " has no fields");
        } else if (r.fields().size() > MAX_FIELDS_PER_ROW) {
          errors.add(
              where + ": " + rowName + " has " + r.fields().size() + " fields (max " + MAX_FIELDS_PER_ROW + ")");
        }

        List<CardField> fields = new ArrayList<>();
        for (FieldSpec f : r.fields()) {
          total++;
          String key = f.fieldKey() == null ? "" : f.fieldKey().trim();
          if (key.isEmpty()) {
            unknown.add("<blank>");
          } else if (!seenKeys.add(key)) {
            duplicates.add(key);
          } else {
            Optional<PrintableField> catalogField = catalog.find(key);
            if (catalogField.isEmpty()) {
              unknown.add(key);
            } else if (!catalogField.get().usableFor(FieldUsage.CARD)) {
              notForCards.add(key);
            } else if (surface.excludes(key)) {
              errors.add(where + ": field " + key + " is excluded on surface " + surface.surfaceId());
            }
          }
          if (LenientBoolean.isInvalid(f.showLabel())) {
            errors.add(where + ": showLabel for " + key + " must be true or false");
          }
          if (f.labelOverride() != null && f.labelOverride().length() > MAX_TEXT_LENGTH) {
            errors.add(where + ": label override for " + key + " exceeds " + MAX_TEXT_LENGTH + " characters");
          }
          Emphasis emphasis = Emphasis.NORMAL;
          if (f.emphasis() != null) {
            Optional<Emphasis> parsed = Emphasis.parse(f.emphasis());
            if (parsed.isPresent()) {
              emphasis = parsed.get();
            } else {
              errors.add(where + ": " + EMPHASIS_ERROR);
            }
          }
          Boolean showLabel = LenientBoolean.valueOf(f.showLabel());
          fields.add(new CardField(key, showLabel == null || showLabel, f.labelOverride(), emphasis));
        }
        rows.add(new CardRow(fields));
      }
      Boolean divider = LenientBoolean.valueOf(s.dividerAbove());
      sections.add(new CardSection(sectionId, s.title(), divider != null && divider, rows));
    }

    if (!unknown.isEmpty()) {
      errors.add(where + ": unknown fields: " + String.join(", ", unknown));
    }
    if (!notForCards.isEmpty()) {
      errors.add(where + ": fields not available on cards: " + String.join(", ", notForCards));
    }
    if (!duplicates.isEmpty()) {
      errors.add(where + ": duplicate fields: " + String.join(", ", duplicates));
    }
    if (total > MAX_FIELDS_TOTAL) {
      errors.add(where + ": at most " + MAX_FIELDS_TOTAL + " fields allowed; " + total + " enabled");
    }

    CardOptions options = validateOptions(spec.options(), where, errors);

    return errors.size() == before ? new CardLayout(sections, options) : null;
  }

  // ---- options -------------------------------------------------------------------------------

  private static CardOptions validateOptions(OptionsSpec spec, String where, Set<String> errors) {
    CardOptions defaults = CardLayoutDefaults.DEFAULT_OPTIONS;
    if (spec == null) {
      return defaults;
    }
    CardBlankValueBehavior blank = defaults.blankValueBehavior();
    if (spec.blankValueBehavior() != null) {
      Optional<CardBlankValueBehavior> parsed = CardBlankValueBehavior.parse(spec.blankValueBehavior());
      if (parsed.isPresent()) {
        blank = parsed.get();
      } else {
        errors.add(where + ": " + BLANK_BEHAVIOUR_ERROR);
      }
    }
    if (LenientBoolean.isInvalid(spec.showAttributeChips())) {
      errors.add(where + ": showAttributeChips must be true or false");
    }
    if (LenientBoolean.isInvalid(spec.showDescription())) {
      errors.add(where + ": showDescription must be true or false");
    }
    Boolean chips = LenientBoolean.valueOf(spec.showAttributeChips());
    Boolean desc = LenientBoolean.valueOf(spec.showDescription());
    return new CardOptions(
        blank,
        chips == null ? defaults.showAttributeChips() : chips,
        desc == null ? defaults.showDescription() : desc);
  }
}
