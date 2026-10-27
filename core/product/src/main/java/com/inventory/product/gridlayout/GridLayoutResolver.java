package com.inventory.product.gridlayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Turns a saved (possibly stale) column list into the full layout served to the frontend.
 *
 * <p>Never fails: unknown and duplicate keys are dropped, widths are clamped to the column's limits,
 * columns that cannot be hidden are shown, {@code FIRST}/{@code LAST} columns go back to their ends,
 * and catalog columns the saved list lacks (new schema fields, columns added later) are placed after
 * their nearest preceding catalog neighbour with default width. This is how a layout survives
 * schema changes without being keyed by schema version.
 */
@Component
public class GridLayoutResolver {

  public List<GridColumnState> resolve(
      List<GridColumnState> saved, List<GridColumnDefinition> catalog) {
    Map<String, GridColumnDefinition> byKey = new LinkedHashMap<>();
    catalog.forEach(c -> byKey.put(c.key(), c));
    Map<String, GridColumnState> savedByKey = new LinkedHashMap<>();
    for (GridColumnState s : saved == null ? List.<GridColumnState>of() : saved) {
      if (s != null && byKey.containsKey(s.key())) {
        savedByKey.putIfAbsent(s.key(), s);
      }
    }

    List<GridColumnState> middle = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (GridColumnState s : savedByKey.values()) {
      GridColumnDefinition def = byKey.get(s.key());
      if (def.position() == GridColumnPosition.ANY && seen.add(def.key())) {
        middle.add(stateFor(def, s));
      }
    }
    for (int i = 0; i < catalog.size(); i++) {
      GridColumnDefinition def = catalog.get(i);
      if (def.position() != GridColumnPosition.ANY || seen.contains(def.key())) {
        continue;
      }
      middle.add(insertionIndex(middle, catalog, i), stateFor(def, null));
      seen.add(def.key());
    }

    List<GridColumnState> resolved = new ArrayList<>();
    for (GridColumnDefinition def : catalog) {
      if (def.position() == GridColumnPosition.FIRST) {
        resolved.add(stateFor(def, savedByKey.get(def.key())));
      }
    }
    resolved.addAll(middle);
    for (GridColumnDefinition def : catalog) {
      if (def.position() == GridColumnPosition.LAST) {
        resolved.add(stateFor(def, savedByKey.get(def.key())));
      }
    }
    return List.copyOf(resolved);
  }

  /** The catalog default: every column at default width, none hidden. */
  public List<GridColumnState> defaults(List<GridColumnDefinition> catalog) {
    return resolve(List.of(), catalog);
  }

  private static int insertionIndex(
      List<GridColumnState> out, List<GridColumnDefinition> catalog, int catalogIndex) {
    for (int j = catalogIndex - 1; j >= 0; j--) {
      String prev = catalog.get(j).key();
      for (int k = 0; k < out.size(); k++) {
        if (out.get(k).key().equals(prev)) {
          return k + 1;
        }
      }
    }
    return 0;
  }

  private static GridColumnState stateFor(GridColumnDefinition def, GridColumnState saved) {
    if (saved == null) {
      return new GridColumnState(def.key(), def.defaultWidthPx(), false);
    }
    return new GridColumnState(
        def.key(), clamp(saved.widthPx(), def), saved.hidden() && def.hideable());
  }

  static int clamp(int widthPx, GridColumnDefinition def) {
    if (widthPx <= 0) {
      return def.defaultWidthPx();
    }
    return Math.max(def.minWidthPx(), Math.min(def.maxWidthPx(), widthPx));
  }
}
