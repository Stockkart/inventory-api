package com.inventory.product.utils;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Flags a stock-in line whose cost is not below the price it will sell at.
 *
 * <p>Such a lot cannot be sold at a profit, and every sale from it reads as a loss in the cart. The
 * usual cause is a cost keyed at the list price with the bill's trade discount left off, which the
 * operator can settle in seconds while the bill is still in their hand — and not at all once the
 * goods are on the shelf and the paper is filed.
 *
 * <p>Advisory only. Stock-in is never blocked: the goods have physically arrived, and refusing to
 * record them over a price that may yet be correct costs more than the wrong price does.
 */
public final class CostPriceSanity {

  private CostPriceSanity() {}

  /**
   * @param productName name as entered, used only to name the line in the message
   * @param costPrice what the shop paid per unit
   * @param sellingPrice what the shop will sell it for per unit
   * @return the warning, or empty when the line is priced sanely or cannot be judged
   */
  public static Optional<String> check(
      String productName, BigDecimal costPrice, BigDecimal sellingPrice) {
    if (costPrice == null || sellingPrice == null || sellingPrice.signum() <= 0) {
      return Optional.empty();
    }
    if (costPrice.compareTo(sellingPrice) < 0) {
      return Optional.empty();
    }
    String line =
        productName == null || productName.isBlank() ? "This line" : productName.trim();
    return Optional.of(
        line
            + ": cost "
            + costPrice.stripTrailingZeros().toPlainString()
            + " is not below the selling price "
            + sellingPrice.stripTrailingZeros().toPlainString()
            + " — it cannot be sold at a profit. Check the bill for a discount not applied to this line.");
  }
}
