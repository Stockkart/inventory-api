package com.inventory.product.search;

import java.util.List;
import java.util.Set;

/**
 * Search metadata attached to a catalog field (advanced-product-search R1.1).
 *
 * @param source where the value lives
 * @param type what kind of value it is; implies the allowed operators
 * @param path the Mongo field path at the pipeline stage where the field is visible, e.g. {@code
 *     product.companyName}, {@code location}, {@code ext.expiryDate}, {@code stockState}
 * @param facetable whether the engine can return value counts for this field
 * @param sortable whether results can be sorted on this field
 * @param enumValues allowed values with labels; empty unless {@link #type} is {@link
 *     SearchFieldType#ENUM}
 */
public record SearchSpec(
    SearchSource source,
    SearchFieldType type,
    String path,
    boolean facetable,
    boolean sortable,
    List<EnumValue> enumValues) {

  public SearchSpec {
    if (source == null || type == null) {
      throw new IllegalArgumentException("SearchSpec needs a source and a type");
    }
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("SearchSpec needs a Mongo path");
    }
    enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
    if (type == SearchFieldType.ENUM && enumValues.isEmpty()) {
      throw new IllegalArgumentException("Enum search field " + path + " needs its values");
    }
  }

  /** One allowed value of an enum field. */
  public record EnumValue(String value, String label) {
    public EnumValue {
      label = label == null || label.isBlank() ? value : label;
    }

    public static EnumValue of(String value) {
      return new EnumValue(value, value);
    }
  }

  public Set<FilterOp> operators() {
    return type.operators();
  }

  public boolean accepts(FilterOp op) {
    return op != null && type.operators().contains(op);
  }

  public boolean allowsValue(String value) {
    return type != SearchFieldType.ENUM || enumValues.stream().anyMatch(v -> v.value().equals(value));
  }

  // ---- factories -------------------------------------------------------------------------------

  public static SearchSpec text(SearchSource source, String path, boolean facetable, boolean sortable) {
    return new SearchSpec(source, SearchFieldType.TEXT, path, facetable, sortable, List.of());
  }

  public static SearchSpec number(SearchSource source, String path, boolean sortable) {
    return new SearchSpec(source, SearchFieldType.NUMBER, path, false, sortable, List.of());
  }

  public static SearchSpec date(SearchSource source, String path, boolean sortable) {
    return new SearchSpec(source, SearchFieldType.DATE, path, false, sortable, List.of());
  }

  public static SearchSpec enumeration(
      SearchSource source, String path, boolean sortable, List<EnumValue> values) {
    return new SearchSpec(source, SearchFieldType.ENUM, path, true, sortable, values);
  }

  /** Same spec, relocated to another source and path (a vertical field shadowing a core one). */
  public SearchSpec relocate(SearchSource newSource, String newPath) {
    return new SearchSpec(newSource, type, newPath, facetable, sortable, enumValues);
  }

  public SearchSpec withSortable(boolean newSortable) {
    return new SearchSpec(source, type, path, facetable, newSortable, enumValues);
  }
}
