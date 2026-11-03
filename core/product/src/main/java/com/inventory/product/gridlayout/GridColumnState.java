package com.inventory.product.gridlayout;

/** One column as laid out by the shop: its width and whether it is hidden. List order is display order. */
public record GridColumnState(String key, int widthPx, boolean hidden) {}
