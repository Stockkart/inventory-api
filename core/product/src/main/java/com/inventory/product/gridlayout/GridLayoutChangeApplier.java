package com.inventory.product.gridlayout;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.rest.dto.request.GridLayoutChangeRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Checks one column change against the grid's catalog and applies it to a resolved layout. Throws
 * {@link ValidationException} when the change breaks a column rule; the layout is then left as it
 * was.
 */
@Component
public class GridLayoutChangeApplier {

  public List<GridColumnState> apply(
      GridLayoutChangeRequest change,
      List<GridColumnState> resolved,
      List<GridColumnDefinition> catalog) {
    if (change == null || !StringUtils.hasText(change.type())) {
      throw new ValidationException("Grid layout change: type is required");
    }
    Map<String, GridColumnDefinition> byKey =
        catalog.stream().collect(Collectors.toMap(GridColumnDefinition::key, Function.identity()));
    GridColumnDefinition def = byKey.get(change.key());
    if (def == null) {
      throw new ValidationException("Grid layout change: unknown column " + change.key());
    }
    return switch (change.type()) {
      case "RESIZE" -> resize(change, def, resolved);
      case "SET_HIDDEN" -> setHidden(change, def, resolved);
      case "MOVE" -> move(change, def, resolved, byKey);
      default ->
          throw new ValidationException(
              "Grid layout change: unknown type "
                  + change.type()
                  + " (expected RESIZE, SET_HIDDEN or MOVE)");
    };
  }

  private static List<GridColumnState> resize(
      GridLayoutChangeRequest change, GridColumnDefinition def, List<GridColumnState> resolved) {
    Integer width = change.widthPx();
    if (width == null || width < def.minWidthPx() || width > def.maxWidthPx()) {
      throw new ValidationException(
          "Grid layout change: width of "
              + def.key()
              + " must be between "
              + def.minWidthPx()
              + " and "
              + def.maxWidthPx()
              + " px");
    }
    return replace(resolved, def.key(), s -> new GridColumnState(s.key(), width, s.hidden()));
  }

  private static List<GridColumnState> setHidden(
      GridLayoutChangeRequest change, GridColumnDefinition def, List<GridColumnState> resolved) {
    if (change.hidden() == null) {
      throw new ValidationException("Grid layout change: hidden is required for SET_HIDDEN");
    }
    boolean hidden = change.hidden();
    if (hidden && !def.hideable()) {
      throw new ValidationException(
          "Grid layout change: " + def.key() + " is required and cannot be hidden");
    }
    return replace(resolved, def.key(), s -> new GridColumnState(s.key(), s.widthPx(), hidden));
  }

  private static List<GridColumnState> move(
      GridLayoutChangeRequest change,
      GridColumnDefinition def,
      List<GridColumnState> resolved,
      Map<String, GridColumnDefinition> byKey) {
    if (!def.reorderable()) {
      throw new ValidationException("Grid layout change: " + def.key() + " cannot be moved");
    }
    String beforeKey = change.beforeKey();
    if (def.key().equals(beforeKey)) {
      throw new ValidationException("Grid layout change: a column cannot move before itself");
    }
    if (beforeKey != null) {
      GridColumnDefinition before = byKey.get(beforeKey);
      if (before == null) {
        throw new ValidationException("Grid layout change: unknown column " + beforeKey);
      }
      if (before.position() == GridColumnPosition.FIRST) {
        throw new ValidationException(
            "Grid layout change: nothing can move before " + beforeKey);
      }
    }
    List<GridColumnState> out = new ArrayList<>(resolved);
    GridColumnState moving =
        out.stream().filter(s -> s.key().equals(def.key())).findFirst().orElseThrow();
    out.remove(moving);
    int at = out.size();
    for (int i = 0; i < out.size(); i++) {
      String k = out.get(i).key();
      boolean trailing = byKey.get(k).position() == GridColumnPosition.LAST;
      if (k.equals(beforeKey) || (beforeKey == null && trailing)) {
        at = i;
        break;
      }
    }
    out.add(at, moving);
    return List.copyOf(out);
  }

  private static List<GridColumnState> replace(
      List<GridColumnState> resolved, String key, Function<GridColumnState, GridColumnState> fn) {
    return resolved.stream().map(s -> s.key().equals(key) ? fn.apply(s) : s).toList();
  }
}
