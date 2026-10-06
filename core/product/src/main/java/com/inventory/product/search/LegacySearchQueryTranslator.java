package com.inventory.product.search;

import com.inventory.product.labels.FieldUsage;
import com.inventory.product.labels.LabelFieldKeys;
import com.inventory.product.rest.dto.request.SearchRequest;
import com.inventory.product.rest.dto.request.SearchRequest.FilterGroup;
import com.inventory.product.search.SearchFieldCatalogService.ShopSearchContext;
import com.inventory.product.utils.InventorySearchQueryParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * Turns the old {@code GET /inventory/search?q=…&field=value} query string into a {@link
 * SearchRequest} so existing callers keep working on the new engine (advanced-product-search R2.6,
 * R8.1).
 *
 * <ul>
 *   <li>{@code q} → {@code text} in pattern mode (the old search was a plain "contains").
 *   <li>Every other key → one {@code in} group with one value. Vertical schema keys are accepted
 *       bare ({@code brand=X}) and resolved to their catalog key ({@code vertical.brand}).
 *   <li>{@code expiryBefore} / {@code expiryAfter} → {@code between} on the expiry field; {@code
 *       nearExpiryDays} → {@code withinDays}.
 *   <li>{@code sort}, {@code limit}/{@code size}, {@code page}, {@code includeZeroStock} carried over.
 * </ul>
 */
public final class LegacySearchQueryTranslator {

  public static final String SURFACE = "legacy-get";

  private LegacySearchQueryTranslator() {}

  public static SearchRequest translate(Map<String, String> query, ShopSearchContext ctx) {
    InventorySearchQueryParser.Parsed parsed = InventorySearchQueryParser.parse(query);
    List<FilterGroup> groups = new ArrayList<>();
    String expiryFrom = null;
    String expiryTo = null;
    String withinDays = null;

    for (Map.Entry<String, String> e : parsed.fieldFilters().entrySet()) {
      String key = e.getKey();
      String value = e.getValue();
      switch (key) {
        case "expiryAfter" -> expiryFrom = value;
        case "expiryBefore" -> expiryTo = value;
        case "nearExpiryDays" -> withinDays = value;
        default -> groups.add(new FilterGroup(resolveKey(key, ctx), "in", List.of(value), null, null));
      }
    }
    if (withinDays != null) {
      groups.add(new FilterGroup(LabelFieldKeys.EXPIRY_DATE, "withinDays", List.of(withinDays), null, null));
    } else if (expiryFrom != null || expiryTo != null) {
      groups.add(new FilterGroup(LabelFieldKeys.EXPIRY_DATE, "between", List.of(), expiryFrom, expiryTo));
    }

    return new SearchRequest(
        parsed.q(),
        TextMode.PATTERN.wireName(),
        groups,
        MatchMode.ALL.wireName(),
        List.of(),
        resolveSort(parsed.sort(), ctx),
        parsed.page(),
        parsed.limit(),
        parsed.includeZeroStock(),
        SURFACE);
  }

  /** {@code brand:asc} → {@code vertical.brand:asc}; direction and unknown keys pass through. */
  static String resolveSort(String sort, ShopSearchContext ctx) {
    if (!StringUtils.hasText(sort)) {
      return null;
    }
    String[] parts = sort.trim().split(":", 2);
    String key = resolveKey(parts[0].trim(), ctx);
    return parts.length > 1 ? key + ":" + parts[1].trim() : key;
  }

  /** A bare schema key such as {@code brand} becomes {@code vertical.brand} when that is the catalog key. */
  static String resolveKey(String key, ShopSearchContext ctx) {
    if (!StringUtils.hasText(key) || ctx == null) {
      return key;
    }
    if (ctx.catalog().findForUsage(key, FieldUsage.SEARCH).isPresent()) {
      return key;
    }
    String vertical = LabelFieldKeys.verticalKey(key);
    if (ctx.catalog().findForUsage(vertical, FieldUsage.SEARCH).isPresent()) {
      return vertical;
    }
    return key; // unknown — the validator will name it
  }
}
