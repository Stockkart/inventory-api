package com.inventory.product.labels;

/**
 * How a {@link PrintMedia#ROLL} shop's label stock is laid out across the web: how many labels sit
 * side by side and the gap between them. Saved with the layout; resolved against the sticker size
 * into a {@link RollSpec} by {@link RollLayoutCalculator}.
 *
 * <p>Absent (a {@code null} setup) means the legacy single-column roll behaviour where the browser
 * and printer driver decide the page: the renderer emits no explicit page box.
 *
 * @param labelsAcross labels per row, {@link RollLayoutCalculator#MIN_LABELS_ACROSS} to {@link
 *     RollLayoutCalculator#MAX_LABELS_ACROSS}
 * @param columnGapMm horizontal gap between neighbouring labels in millimetres, {@code 0} to
 *     {@link RollLayoutCalculator#MAX_COLUMN_GAP_MM}
 */
public record RollSetup(int labelsAcross, double columnGapMm) {}
