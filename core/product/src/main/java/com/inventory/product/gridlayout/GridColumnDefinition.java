package com.inventory.product.gridlayout;

/**
 * One column a grid can show, with the rules a saved layout must respect.
 *
 * @param key stable column key; schema fields use {@code field:<schemaKey>}
 * @param mandatory a mandatory column can be moved and resized but never hidden
 * @param movable false for columns pinned to the start or end of the grid
 */
public record GridColumnDefinition(
    String key,
    boolean mandatory,
    boolean movable,
    int defaultWidthPx,
    int minWidthPx,
    int maxWidthPx) {}
