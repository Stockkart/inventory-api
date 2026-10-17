package com.inventory.product.gridlayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.rest.dto.request.SaveGridLayoutRequest;
import com.inventory.product.rest.dto.request.SaveGridLayoutRequest.ColumnSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

class GridLayoutValidatorTest {

  private final GridLayoutValidator validator = new GridLayoutValidator();
  private final List<GridColumnDefinition> catalog = GridLayoutTestFixtures.catalog();

  @Test
  void acceptsReorderResizeAndHidingOptionalColumns() {
    List<GridColumnState> out =
        validator.validate(
            request(
                new ColumnSpec("rowNo", 60, false),
                new ColumnSpec("product", 320, false),
                new ColumnSpec("barcode", null, true),
                new ColumnSpec("actions", 150, false)),
            catalog);
    assertThat(out)
        .containsExactly(
            new GridColumnState("rowNo", 60, false),
            new GridColumnState("product", 320, false),
            new GridColumnState("barcode", 200, true),
            new GridColumnState("actions", 150, false));
  }

  @Test
  void rejectsHidingMandatoryColumns() {
    assertThatThrownBy(
            () -> validator.validate(request(new ColumnSpec("field:batchNo", 140, true)), catalog))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("field:batchNo is mandatory and cannot be hidden");
  }

  @Test
  void rejectsUnknownDuplicateAndOutOfRangeColumns() {
    assertThatThrownBy(
            () ->
                validator.validate(
                    request(
                        new ColumnSpec("ghost", 100, false),
                        new ColumnSpec("hsn", 100, false),
                        new ColumnSpec("hsn", 100, false),
                        new ColumnSpec("qty", 10, false),
                        new ColumnSpec(" ", 100, false)),
                    catalog))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("unknown column ghost")
        .hasMessageContaining("hsn is listed twice")
        .hasMessageContaining("width of qty must be between 48 and 600 px")
        .hasMessageContaining("columns[4]: key is required");
  }

  @Test
  void rejectsMovingPinnedColumns() {
    assertThatThrownBy(
            () ->
                validator.validate(
                    request(new ColumnSpec("product", 200, false), new ColumnSpec("rowNo", 48, false),
                        new ColumnSpec("actions", 140, false), new ColumnSpec("qty", 80, false)),
                    catalog))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("rowNo cannot be moved")
        .hasMessageContaining("actions cannot be moved");
  }

  private static SaveGridLayoutRequest request(ColumnSpec... specs) {
    return new SaveGridLayoutRequest(List.of(specs));
  }
}
