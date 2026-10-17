package com.inventory.product.gridlayout;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.rest.dto.request.SaveGridLayoutRequest;
import com.inventory.product.rest.dto.request.SaveGridLayoutRequest.ColumnSpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Checks a submitted grid layout against the grid's catalog. All problems are collected and thrown
 * together as one {@link ValidationException}; nothing is saved when any is found.
 */
@Component
public class GridLayoutValidator {

  public List<GridColumnState> validate(
      SaveGridLayoutRequest request, List<GridColumnDefinition> catalog) {
    Map<String, GridColumnDefinition> byKey = new LinkedHashMap<>();
    catalog.forEach(c -> byKey.put(c.key(), c));

    List<String> errors = new ArrayList<>();
    List<GridColumnState> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    List<ColumnSpec> columns = request == null ? List.of() : request.columns();
    for (int i = 0; i < columns.size(); i++) {
      ColumnSpec spec = columns.get(i);
      String at = "columns[" + i + "]";
      if (spec == null || !StringUtils.hasText(spec.key())) {
        errors.add(at + ": key is required");
        continue;
      }
      GridColumnDefinition def = byKey.get(spec.key());
      if (def == null) {
        errors.add(at + ": unknown column " + spec.key());
        continue;
      }
      if (!seen.add(def.key())) {
        errors.add(at + ": column " + def.key() + " is listed twice");
        continue;
      }
      int width = spec.widthPx() == null ? def.defaultWidthPx() : spec.widthPx();
      if (width < def.minWidthPx() || width > def.maxWidthPx()) {
        errors.add(
            at
                + ": width of "
                + def.key()
                + " must be between "
                + def.minWidthPx()
                + " and "
                + def.maxWidthPx()
                + " px");
      }
      boolean hidden = Boolean.TRUE.equals(spec.hidden());
      if (hidden && def.mandatory()) {
        errors.add(at + ": " + def.key() + " is mandatory and cannot be hidden");
      }
      if (!def.movable() && !pinnedInPlace(def, i, columns.size(), catalog)) {
        errors.add(at + ": " + def.key() + " cannot be moved");
      }
      out.add(new GridColumnState(def.key(), width, hidden));
    }
    if (!errors.isEmpty()) {
      throw new ValidationException("Invalid grid layout: " + String.join("; ", errors));
    }
    return List.copyOf(out);
  }

  /** A pinned column is valid only at the start (if it leads the catalog) or the end. */
  private static boolean pinnedInPlace(
      GridColumnDefinition def, int index, int size, List<GridColumnDefinition> catalog) {
    boolean leads = !catalog.isEmpty() && catalog.get(0).key().equals(def.key());
    return leads ? index == 0 : index == size - 1;
  }
}
