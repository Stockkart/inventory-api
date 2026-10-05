package com.inventory.product.rest.dto.request;

import java.util.List;
import java.util.Map;

/**
 * Body of {@code PUT /api/v1/shops/active-shop/card-layouts/{surfaceId}} (configurable-product-card
 * Req 3, 4.1).
 *
 * <p>There is deliberately no {@code shopId} or {@code surfaceId} in the body: both come from the
 * authenticated request context and the path. Enum-like options are bound as strings and booleans
 * as {@link LenientBoolean} so a malformed value becomes a named validation message from {@code
 * CardLayoutValidator} rather than a bare JSON parse failure.
 *
 * @param variants layouts keyed by variant name ({@code "REGULAR"}, {@code "BASIC"}); a missing
 *     variant on a billing-mode-aware surface is filled with the default
 */
public record SaveCardLayoutRequest(Map<String, LayoutSpec> variants) {

  public SaveCardLayoutRequest {
    variants = variants == null ? Map.of() : Map.copyOf(variants);
  }

  /** One variant's layout as submitted. */
  public record LayoutSpec(List<SectionSpec> sections, OptionsSpec options) {
    public LayoutSpec {
      sections = sections == null ? List.of() : List.copyOf(sections);
    }
  }

  /** One section as submitted. */
  public record SectionSpec(
      String id, String title, LenientBoolean dividerAbove, List<RowSpec> rows) {
    public SectionSpec {
      rows = rows == null ? List.of() : List.copyOf(rows);
    }
  }

  /** One row as submitted. */
  public record RowSpec(List<FieldSpec> fields) {
    public RowSpec {
      fields = fields == null ? List.of() : List.copyOf(fields);
    }
  }

  /** One field reference as submitted. */
  public record FieldSpec(
      String fieldKey, LenientBoolean showLabel, String labelOverride, String emphasis) {}

  /** Layout options as submitted; any component may be {@code null} meaning "use default". */
  public record OptionsSpec(
      String blankValueBehavior, LenientBoolean showAttributeChips, LenientBoolean showDescription) {}
}
