package com.inventory.pluginengine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Filter/sort query for extension-backed inventory search. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventorySearchQuery {

  /** Core text search (name/barcode/company) — intersected with extension filter hits when both set. */
  private String q;

  @Builder.Default
  private Map<String, String> filters = new LinkedHashMap<>();

  /** e.g. {@code expiryDate:asc} */
  private String sort;

  @Builder.Default
  private int limit = 50;

  private String cursor;

  /** When set, results are limited to these core inventory ids (e.g. after text search). */
  private Set<String> restrictInventoryIds;

  /** Offset pagination (list API); ignored when {@code cursor} is set. */
  @Builder.Default
  private int skip = 0;
}
