package com.inventory.product.service;

import com.inventory.common.util.GstMath;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.utils.CheckoutUtils;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Computes taxable, tax, COGS, and refund tender splits for customer returns. */
final class SalesReturnValuation {

  private SalesReturnValuation() {}

  record LineAmounts(
      BigDecimal taxable,
      BigDecimal cgst,
      BigDecimal sgst,
      BigDecimal igst,
      BigDecimal cogs,
      BigDecimal lineTotal) {}

  record AmountTotals(
      BigDecimal taxableTotal,
      BigDecimal cgstTotal,
      BigDecimal sgstTotal,
      BigDecimal igstTotal,
      BigDecimal cogsTotal,
      BigDecimal returnTotal,
      BigDecimal roundOff) {}

  /**
   * A returned line's taxable value and tax. A return reverses the sale's tax under the head the
   * sale charged it: IGST at the combined rate when the sale was interstate, else CGST and SGST.
   */
  static LineAmounts lineAmounts(
      PurchaseItem purchaseItem,
      int refundBaseQty,
      int refundDisplayQty,
      BillingMode billingMode,
      boolean interstate) {
    BigDecimal billableQty =
        prorateBillableQuantity(purchaseItem, refundBaseQty, refundDisplayQty);
    BigDecimal effectivePrice = CheckoutUtils.getEffectiveSellingPricePerUnit(purchaseItem);
    BigDecimal taxable = effectivePrice.multiply(billableQty).setScale(2, RoundingMode.HALF_UP);
    if (purchaseItem.getSaleAdditionalDiscount() != null
        && purchaseItem.getSaleAdditionalDiscount().compareTo(BigDecimal.ZERO) > 0) {
      taxable =
          taxable
              .multiply(
                  BigDecimal.ONE.subtract(
                      purchaseItem
                          .getSaleAdditionalDiscount()
                          .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)))
              .setScale(2, RoundingMode.HALF_UP);
    }

    BigDecimal cgst = BigDecimal.ZERO;
    BigDecimal sgst = BigDecimal.ZERO;
    BigDecimal igst = BigDecimal.ZERO;
    if (CheckoutUtils.isTaxApplicableForItem(purchaseItem, billingMode)) {
      BigDecimal cgstRate = GstMath.parseGstRate(purchaseItem.getCgst());
      BigDecimal sgstRate = GstMath.parseGstRate(purchaseItem.getSgst());
      if (interstate) {
        igst = GstMath.taxOnExclusive(taxable, cgstRate.add(sgstRate));
      } else {
        cgst = GstMath.taxOnExclusive(taxable, cgstRate);
        sgst = GstMath.taxOnExclusive(taxable, sgstRate);
      }
    }

    BigDecimal cogs = prorateCogs(purchaseItem, refundBaseQty);
    BigDecimal lineTotal = taxable.add(cgst).add(sgst).add(igst).setScale(2, RoundingMode.HALF_UP);
    return new LineAmounts(taxable, cgst, sgst, igst, cogs, lineTotal);
  }

  static AmountTotals aggregate(List<LineAmounts> lines) {
    BigDecimal taxable =
        lines.stream().map(LineAmounts::taxable).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal cgst = lines.stream().map(LineAmounts::cgst).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal sgst = lines.stream().map(LineAmounts::sgst).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal igst = lines.stream().map(LineAmounts::igst).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal cogs = lines.stream().map(LineAmounts::cogs).reduce(BigDecimal.ZERO, BigDecimal::add);

    taxable = taxable.setScale(2, RoundingMode.HALF_UP);
    cgst = cgst.setScale(2, RoundingMode.HALF_UP);
    sgst = sgst.setScale(2, RoundingMode.HALF_UP);
    igst = igst.setScale(2, RoundingMode.HALF_UP);
    cogs = cogs.setScale(2, RoundingMode.HALF_UP);

    BigDecimal preRound = taxable.add(cgst).add(sgst).add(igst).setScale(2, RoundingMode.HALF_UP);
    BigDecimal returnTotal = roundToWholeRupee(preRound);
    BigDecimal roundOff = returnTotal.subtract(preRound).setScale(4, RoundingMode.HALF_UP);

    return new AmountTotals(taxable, cgst, sgst, igst, cogs, returnTotal, roundOff);
  }

  private static BigDecimal prorateBillableQuantity(
      PurchaseItem purchaseItem, int refundBaseQty, int refundDisplayQty) {
    int purchasedBase = purchaseItem.getBaseQuantity() != null ? purchaseItem.getBaseQuantity() : 0;
    if (purchasedBase <= 0) {
      return BigDecimal.valueOf(Math.max(refundDisplayQty, 0));
    }
    BigDecimal ratio =
        BigDecimal.valueOf(refundBaseQty)
            .divide(BigDecimal.valueOf(purchasedBase), 8, RoundingMode.HALF_UP);
    return CheckoutUtils.getBillableQuantityAsDecimal(purchaseItem).multiply(ratio);
  }

  private static BigDecimal prorateCogs(PurchaseItem purchaseItem, int refundBaseQty) {
    int purchasedBase = purchaseItem.getBaseQuantity() != null ? purchaseItem.getBaseQuantity() : 0;
    if (purchaseItem.getCostTotal() != null && purchasedBase > 0) {
      return purchaseItem
          .getCostTotal()
          .multiply(BigDecimal.valueOf(refundBaseQty))
          .divide(BigDecimal.valueOf(purchasedBase), 2, RoundingMode.HALF_UP);
    }
    if (purchaseItem.getCostPrice() != null && refundBaseQty > 0) {
      BigDecimal displayQty =
          BigDecimal.valueOf(refundBaseQty)
              .divide(
                  BigDecimal.valueOf(
                      purchaseItem.getUnitFactor() != null && purchaseItem.getUnitFactor() > 0
                          ? purchaseItem.getUnitFactor()
                          : 1),
                  4,
                  RoundingMode.HALF_UP);
      return purchaseItem.getCostPrice().multiply(displayQty).setScale(2, RoundingMode.HALF_UP);
    }
    return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
  }


  private static BigDecimal roundToWholeRupee(BigDecimal amount) {
    if (amount == null) return BigDecimal.ZERO;
    return amount.setScale(0, RoundingMode.HALF_UP);
  }

}
