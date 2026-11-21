package com.inventory.product.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Shop-level product card layout for one card surface (configurable-product-card Req 4.1, 4.7).
 *
 * <p>Exactly one document per (shop, surface); created only when the shop saves a layout. Only
 * {@code fieldKey}s are stored — never labels, paths or types — so the document survives catalog
 * evolution. Variants are keyed by {@code CardVariant} name ({@code REGULAR}, {@code BASIC}).
 *
 * <p>Nested shapes are plain mutable classes so Spring Data maps them without custom converters;
 * conversion to and from the immutable domain records lives in {@code CardLayoutDocuments}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "shop_card_layouts")
@CompoundIndex(name = "shop_surface_idx", def = "{'shopId': 1, 'surfaceId': 1}", unique = true)
public class CardLayoutDocument {

  @Id private String id;

  private String shopId;

  private String surfaceId;

  /** Variant name → layout. */
  private Map<String, LayoutDoc> variants;

  private Instant updatedAt;

  private String updatedByUserId;

  /** One variant's layout. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class LayoutDoc {
    private List<SectionDoc> sections;
    private OptionsDoc options;
  }

  /** One section. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class SectionDoc {
    private String id;
    private String title;
    private Boolean dividerAbove;
    private List<RowDoc> rows;
  }

  /** One row. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class RowDoc {
    private List<FieldDoc> fields;
  }

  /** One field reference. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class FieldDoc {
    private String fieldKey;
    private Boolean showLabel;
    private String labelOverride;
    private String emphasis;
  }

  /** Layout options. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class OptionsDoc {
    private String blankValueBehavior;
    private Boolean showAttributeChips;
    private Boolean showDescription;
  }
}
