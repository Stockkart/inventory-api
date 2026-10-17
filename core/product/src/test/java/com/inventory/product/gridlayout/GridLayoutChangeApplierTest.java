package com.inventory.product.gridlayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.rest.dto.request.GridLayoutChangeRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class GridLayoutChangeApplierTest {

  private final GridLayoutChangeApplier applier = new GridLayoutChangeApplier();
  private final List<GridColumnDefinition> catalog = GridLayoutTestFixtures.catalog();
  private final List<GridColumnState> base = new GridLayoutResolver().defaults(catalog);

  @Test
  void resizeChangesOnlyThatColumn() {
    List<GridColumnState> out = applier.apply(change("RESIZE", "product", 320, null, null), base, catalog);
    assertThat(state(out, "product").widthPx()).isEqualTo(320);
    assertThat(out).hasSameSizeAs(base);
    assertThat(state(out, "barcode")).isEqualTo(state(base, "barcode"));
  }

  @Test
  void resizeOutsideLimitsIsRejected() {
    assertThatThrownBy(() -> applier.apply(change("RESIZE", "qty", 10, null, null), base, catalog))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("between 48 and 600 px");
  }

  @Test
  void optionalColumnsHideRequiredOnesDoNot() {
    List<GridColumnState> out =
        applier.apply(change("SET_HIDDEN", "packaging", null, true, null), base, catalog);
    assertThat(state(out, "packaging").hidden()).isTrue();
    assertThatThrownBy(
            () -> applier.apply(change("SET_HIDDEN", "field:batchNo", null, true, null), base, catalog))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("field:batchNo is required and cannot be hidden");
    assertThatThrownBy(
            () -> applier.apply(change("SET_HIDDEN", "rowNo", null, true, null), base, catalog))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void moveBeforeAnotherColumnOrToTheEnd() {
    List<GridColumnState> before =
        applier.apply(change("MOVE", "packaging", null, null, "field:batchNo"), base, catalog);
    List<String> keys = keys(before);
    assertThat(keys.indexOf("packaging")).isEqualTo(keys.indexOf("field:batchNo") - 1);

    List<String> end = keys(applier.apply(change("MOVE", "barcode", null, null, null), base, catalog));
    assertThat(end.get(end.size() - 2)).isEqualTo("barcode");
    assertThat(end.get(end.size() - 1)).isEqualTo("actions");
  }

  @Test
  void pinnedColumnsCannotMoveAndNothingGoesBeforeTheFirst() {
    assertThatThrownBy(() -> applier.apply(change("MOVE", "actions", null, null, "qty"), base, catalog))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("actions cannot be moved");
    assertThatThrownBy(() -> applier.apply(change("MOVE", "qty", null, null, "rowNo"), base, catalog))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("nothing can move before rowNo");
  }

  @Test
  void unknownTypeOrColumnIsRejected() {
    assertThatThrownBy(() -> applier.apply(change("SWAP", "qty", null, null, null), base, catalog))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("unknown type SWAP");
    assertThatThrownBy(() -> applier.apply(change("RESIZE", "ghost", 100, null, null), base, catalog))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("unknown column ghost");
  }

  static GridLayoutChangeRequest change(
      String type, String key, Integer width, Boolean hidden, String beforeKey) {
    return new GridLayoutChangeRequest(type, key, width, hidden, beforeKey);
  }

  private static GridColumnState state(List<GridColumnState> cols, String key) {
    return cols.stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
  }

  private static List<String> keys(List<GridColumnState> cols) {
    return cols.stream().map(GridColumnState::key).toList();
  }
}
