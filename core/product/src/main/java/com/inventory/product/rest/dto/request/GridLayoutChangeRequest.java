package com.inventory.product.rest.dto.request;

/**
 * Body of {@code PATCH /api/v1/shops/active-shop/grid-layouts/{gridId}}: one change to one column.
 *
 * <p>Each change is absolute (set this width, set hidden, place before that column), so the server
 * applies it to the latest saved layout and two members changing different columns at the same
 * time both keep their change. Two changes to the same property: the later accepted one wins.
 *
 * @param type {@code RESIZE}, {@code SET_HIDDEN} or {@code MOVE}
 * @param key the column changed
 * @param widthPx for {@code RESIZE}
 * @param hidden for {@code SET_HIDDEN}
 * @param beforeKey for {@code MOVE}: the column to place {@code key} in front of; null places it
 *     last among the movable columns
 */
public record GridLayoutChangeRequest(
    String type, String key, Integer widthPx, Boolean hidden, String beforeKey) {}
