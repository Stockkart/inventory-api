package com.inventory.product.gridlayout;

/**
 * One column a grid can show, with the rules a layout must respect. Whether a field is
 * {@code required} to save comes from the vertical schema or the workflow; how it may be laid out
 * ({@code hideable}, {@code reorderable}, {@code position}) is a separate, grid-only rule.
 *
 * @param key stable column key; schema fields use {@code field:<schemaKey>}
 * @param label display name for settings screens (the schema label for schema fields); never
 *     stored with a layout
 * @param required the value must be filled in to save (shown as {@code *})
 * @param hideable the shop may hide the column
 * @param reorderable the shop may move the column
 * @param position {@code FIRST}/{@code LAST} pin a structural column to an end
 */
public record GridColumnDefinition(
    String key,
    String label,
    boolean required,
    boolean hideable,
    boolean reorderable,
    GridColumnPosition position,
    int defaultWidthPx,
    int minWidthPx,
    int maxWidthPx) {

  /** Width limits every grid uses unless a catalog needs other ones. */
  public static final int MIN_WIDTH_PX = 48;

  public static final int MAX_WIDTH_PX = 600;

  /** Structural (row number, actions): never hidden or moved. */
  public static GridColumnDefinition pinned(
      String key, String label, int widthPx, GridColumnPosition position) {
    return new GridColumnDefinition(
        key, label, false, false, false, position, widthPx, MIN_WIDTH_PX, MAX_WIDTH_PX);
  }

  /** Must be filled in to save, so it is never hidden; it may move. */
  public static GridColumnDefinition required(String key, String label, int widthPx) {
    return new GridColumnDefinition(
        key, label, true, false, true, GridColumnPosition.ANY, widthPx, MIN_WIDTH_PX, MAX_WIDTH_PX);
  }

  /** May be hidden and moved. */
  public static GridColumnDefinition optional(String key, String label, int widthPx) {
    return new GridColumnDefinition(
        key, label, false, true, true, GridColumnPosition.ANY, widthPx, MIN_WIDTH_PX, MAX_WIDTH_PX);
  }
}
