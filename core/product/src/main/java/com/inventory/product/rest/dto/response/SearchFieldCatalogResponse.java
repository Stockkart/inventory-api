package com.inventory.product.rest.dto.response;

import com.inventory.product.labels.FieldCatalog;
import com.inventory.product.labels.FieldUsage;
import com.inventory.product.labels.PrintableField;
import com.inventory.product.labels.SourceGroup;
import com.inventory.product.search.FilterOp;
import com.inventory.product.search.SearchFieldType;
import com.inventory.product.search.SearchSource;
import com.inventory.product.search.SearchSpec;
import java.util.Comparator;
import java.util.List;

/**
 * Response of {@code GET /api/v1/inventory/search/fields} (advanced-product-search R1.1): every
 * field the active shop can filter, facet or sort on, plus the default sort.
 *
 * <pre>{@code
 * { "fields": [ { "key": "companyName", "label": "Company", "group": "product", "source": "product",
 *                 "type": "text", "ops": ["in", "matches", "exists"], "facet": true, "sortable": true,
 *                 "values": [] } ],
 *   "defaultSort": "expiryDate:asc", "verticalSchemaLoaded": true }
 * }</pre>
 */
public record SearchFieldCatalogResponse(
    List<Field> fields, String defaultSort, boolean verticalSchemaLoaded) {

  public SearchFieldCatalogResponse {
    fields = fields == null ? List.of() : List.copyOf(fields);
  }

  public static SearchFieldCatalogResponse from(FieldCatalog catalog, String defaultSort) {
    return new SearchFieldCatalogResponse(
        catalog.forUsage(FieldUsage.SEARCH).stream().map(Field::from).toList(),
        defaultSort,
        catalog.verticalSchemaLoaded());
  }

  /** One searchable field. */
  public record Field(
      String key,
      String label,
      SourceGroup group,
      SearchSource source,
      SearchFieldType type,
      List<FilterOp> ops,
      boolean facet,
      boolean sortable,
      List<SearchSpec.EnumValue> values) {

    public Field {
      ops = ops == null ? List.of() : List.copyOf(ops);
      values = values == null ? List.of() : List.copyOf(values);
    }

    static Field from(PrintableField f) {
      SearchSpec s = f.searchSpec();
      return new Field(
          f.fieldKey(),
          f.label(),
          f.sourceGroup(),
          s.source(),
          s.type(),
          s.operators().stream().sorted(Comparator.comparing(FilterOp::ordinal)).toList(),
          s.facetable(),
          s.sortable(),
          s.enumValues());
    }
  }
}
