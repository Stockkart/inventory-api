package com.inventory.product.gridlayout;

/**
 * One column a grid can show, with the rules a layout must respect. Whether a field is
 * {@code required} to save comes from the vertical schema or the workflow; how it may be laid out
 * ({@code hideable}, {@code reorderable}, {@code position}) is a separate, grid-only rule.
 *
 * @param key stable column key; schema fields use {@code field:<schemaKey>}
 * @param required the value must be filled in to save (shown as {@code *})
 * @param hideable the shop may hide the column
 * @param reorderable the shop may move the column
 * @param position {@code FIRST}/{@code LAST} pin a structural column to an end
 */
public record GridColumnDefinition(
    String key,
    boolean required,
    boolean hideable,
    boolean reorderable,
    GridColumnPosition position,
    int defaultWidthPx,
    int minWidthPx,
    int maxWidthPx) {}
