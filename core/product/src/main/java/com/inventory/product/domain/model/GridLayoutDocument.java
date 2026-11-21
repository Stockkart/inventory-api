package com.inventory.product.domain.model;

import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Shop-level column layout for one data grid: column order, widths and hidden columns, shared by
 * every member of the shop.
 *
 * <p>Exactly one document per (shop, grid); created only when the shop changes the layout and
 * deleted on reset. Only column keys are stored — never labels — so the document survives schema
 * and catalog changes; unknown keys are dropped on read.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "shop_grid_layouts")
@CompoundIndex(name = "shop_grid_idx", def = "{'shopId': 1, 'gridId': 1}", unique = true)
public class GridLayoutDocument {

  @Id private String id;

  private String shopId;

  private String gridId;

  /** Optimistic-lock revision; bumped on every save, so concurrent writers retry on the latest. */
  @Version private Long revision;

  /** Display order. */
  private List<ColumnDoc> columns;

  private Instant updatedAt;

  private String updatedByUserId;

  /** One column. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ColumnDoc {
    private String key;
    private Integer widthPx;
    private Boolean hidden;
  }
}
