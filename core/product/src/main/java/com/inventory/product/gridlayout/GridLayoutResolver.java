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
 * mandatory columns are shown, pinned columns go back to their ends, and catalog columns the saved
 * list lacks (new schema fields, columns added later) are placed after their nearest preceding
 * catalog neighbour with default width.
 */
@Component
public class GridLayoutResolver {

  public List<GridColumnState> resolve(
      List<GridColumnState> saved, List<GridColumnDefinition> catalog) {
    Map<String, GridColumnDefinition> byKey = new LinkedHashMap<>();
    catalog.forEach(c -> byKey.put(c.key(), c));

    List<GridColumnState> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (GridColumnState s : saved == null ? List.<GridColumnState>of() : saved) {
      GridColumnDefinition def = s == null ? null : byKey.get(s.key());
      if (def == null || !def.movable() || !seen.add(def.key())) {
        continue;
      }
      out.add(new GridColumnState(def.key(), clamp(s.widthPx(), def), s.hidden() && !def.mandatory()));
    }

    for (int i = 0; i < catalog.size(); i++) {
      GridColumnDefinition def = catalog.get(i);
      if (!def.movable() || seen.contains(def.key())) {
        continue;
      }
      out.add(insertionIndex(out, catalog, i), defaultState(def));
      seen.add(def.key());
    }

    List<GridColumnState> leading = new ArrayList<>();
    List<GridColumnState> trailing = new ArrayList<>();
    boolean beforeMovable = true;
    for (GridColumnDefinition def : catalog) {
      if (def.movable()) {
        beforeMovable = false;
      } else if (beforeMovable) {
        leading.add(defaultStateFrom(saved, def));
      } else {
        trailing.add(defaultStateFrom(saved, def));
      }
    }
    List<GridColumnState> resolved = new ArrayList<>(leading);
    resolved.addAll(out);
    resolved.addAll(trailing);
    return List.copyOf(resolved);
  }

  /** The catalog default: every column at default width, none hidden. */
  public List<GridColumnState> defaults(List<GridColumnDefinition> catalog) {
    return catalog.stream().map(GridLayoutResolver::defaultState).toList();
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

  /** A pinned column keeps its saved width; position and visibility are fixed. */
  private static GridColumnState defaultStateFrom(
      List<GridColumnState> saved, GridColumnDefinition def) {
    if (saved != null) {
      for (GridColumnState s : saved) {
        if (s != null && def.key().equals(s.key())) {
          return new GridColumnState(def.key(), clamp(s.widthPx(), def), false);
        }
      }
    }
    return defaultState(def);
  }

  private static GridColumnState defaultState(GridColumnDefinition def) {
    return new GridColumnState(def.key(), def.defaultWidthPx(), false);
  }

  private static int clamp(int widthPx, GridColumnDefinition def) {
    if (widthPx <= 0) {
      return def.defaultWidthPx();
    }
    return Math.max(def.minWidthPx(), Math.min(def.maxWidthPx(), widthPx));
  }
}
