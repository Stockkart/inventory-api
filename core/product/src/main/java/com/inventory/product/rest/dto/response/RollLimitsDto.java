package com.inventory.product.rest.dto.response;

import com.inventory.product.labels.RollLayoutCalculator;

/**
 * The roll-setup bounds the label layout screen offers, published in the field catalog so the
 * screen never hard-codes the rules {@link RollLayoutCalculator} enforces on save.
 *
 * @param minLabelsAcross fewest labels per roll row
 * @param maxLabelsAcross most labels per roll row
 * @param maxColumnGapMm widest gap between neighbouring labels, in millimetres
 */
public record RollLimitsDto(int minLabelsAcross, int maxLabelsAcross, double maxColumnGapMm) {

  /** The limits currently enforced by {@link RollLayoutCalculator}. */
  public static final RollLimitsDto CURRENT =
      new RollLimitsDto(
          RollLayoutCalculator.MIN_LABELS_ACROSS,
          RollLayoutCalculator.MAX_LABELS_ACROSS,
          RollLayoutCalculator.MAX_COLUMN_GAP_MM);
}
