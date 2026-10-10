package com.inventory.product.utils;

import com.inventory.common.util.GstMath;
import com.inventory.common.constants.PurchaseTaxTreatment;
import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.utils.PricingUtils;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.model.VendorPurchaseInvoiceLine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Works out what a supplier invoice is worth for tax, from its lines.
 *
 * <p>The invoice header is not an input. Its line subtotal and tax total are this calculation's
 * output, so there is no typed figure to weigh against it. Each line is its quantity times its cost
 * after the reductions GST recognises; a bill-level discount is shared across the lines by value;
 * then the tax is added on top, or taken out where the bill's amounts already include it.
 */
public final class PurchaseTaxBasisResolver {

  private PurchaseTaxBasisResolver() {}

  /**
   * Resolves the taxable value and tax of every line on an invoice.
   *
   * @param invoice the supplier invoice, whose lines are read in order
   * @param pricingByInventoryId pricing for each line's lot, giving the rate and any discount
   * @param treatment whether line amounts already contain tax
   * @param interstate true when the supply crosses a state border, so the tax is IGST
   */
  public static PurchaseTaxBasis resolve(
      VendorPurchaseInvoice invoice,
      Function<String, Pricing> pricingByInventoryId,
      PurchaseTaxTreatment treatment,
      boolean interstate) {

    List<VendorPurchaseInvoiceLine> lines =
        invoice.getLines() == null ? List.of() : invoice.getLines();
    if (lines.isEmpty()) {
      return new PurchaseTaxBasis(List.of(), BigDecimal.ZERO);
    }

    List<BigDecimal> rates = new ArrayList<>(lines.size());
    List<BigDecimal> values = new ArrayList<>(lines.size());
    for (VendorPurchaseInvoiceLine line : lines) {
      Pricing pricing = line.getInventoryId() == null ? null
          : pricingByInventoryId.apply(line.getInventoryId());
      rates.add(rateOf(pricing));
      values.add(lineValue(line, pricing));
    }

    BigDecimal discount = applicableOverallDiscount(invoice.getOverallDiscount(), sum(values));
    if (discount.signum() > 0) {
      values = shareOut(values, discount);
    }

    boolean inclusive = PurchaseTaxTreatment.orDefault(treatment) == PurchaseTaxTreatment.INCLUSIVE;
    List<PurchaseTaxBasis.Line> out = new ArrayList<>(lines.size());
    for (int i = 0; i < lines.size(); i++) {
      BigDecimal rate = rates.get(i);
      BigDecimal taxable = inclusive
          ? GstMath.extractFromInclusive(values.get(i), rate).taxable()
          : values.get(i);
      BigDecimal integrated = interstate ? GstMath.taxOnExclusive(taxable, rate) : BigDecimal.ZERO;
      GstMath.IntraStateTax halves = interstate
          ? new GstMath.IntraStateTax(BigDecimal.ZERO, BigDecimal.ZERO)
          : GstMath.splitIntraState(taxable, rate);
      out.add(new PurchaseTaxBasis.Line(
          taxable, rate, halves.centralTax(), halves.stateTax(), integrated));
    }
    return new PurchaseTaxBasis(out, discount);
  }

  /**
   * Quantity times cost after the line's price reductions, or its list cost where none was
   * recorded.
   */
  private static BigDecimal lineValue(VendorPurchaseInvoiceLine line, Pricing pricing) {
    BigDecimal qty = line.getCount() == null
        ? BigDecimal.ZERO : BigDecimal.valueOf(line.getCount());
    BigDecimal discounted = discountedUnitCost(pricing);
    if (discounted != null && pricing.getCostPrice() != null
        && discounted.compareTo(pricing.getCostPrice()) < 0) {
      return money(discounted.multiply(qty));
    }
    BigDecimal listCost = line.getCostPrice() == null ? BigDecimal.ZERO : line.getCostPrice();
    return money(listCost.multiply(qty));
  }

  /**
   * The bill-level discount that can be taken off the lines. One as large as the lines themselves
   * is a data error, and is ignored rather than producing a negative taxable value.
   */
  private static BigDecimal applicableOverallDiscount(BigDecimal discount, BigDecimal linesTotal) {
    if (discount == null || discount.signum() <= 0 || discount.compareTo(linesTotal) >= 0) {
      return BigDecimal.ZERO;
    }
    return money(discount);
  }

  /**
   * Takes a bill-level discount off the lines in proportion to their value. The remainder lands on
   * the last line, so the lines add back to exactly the discounted total.
   */
  private static List<BigDecimal> shareOut(List<BigDecimal> values, BigDecimal discount) {
    BigDecimal total = sum(values);
    List<BigDecimal> out = new ArrayList<>(values.size());
    BigDecimal taken = BigDecimal.ZERO;
    for (BigDecimal value : values) {
      BigDecimal share = value.multiply(discount).divide(total, 2, RoundingMode.HALF_UP);
      out.add(value.subtract(share));
      taken = taken.add(share);
    }
    int last = out.size() - 1;
    out.set(last, out.get(last).subtract(discount.subtract(taken)));
    return out;
  }

  /**
   * What one unit of a lot is worth for tax, from its pricing alone.
   *
   * <p>The fallback for a line recorded before the taxable value was persisted on it. It applies
   * the price reductions GST recognises and, where the supplier billed at MRP, takes the tax back
   * out -- the two things a raw {@code costPrice} does not account for, and between them worth
   * more than a third of the figure on a scheme-discounted inclusive bill.
   */
  public static BigDecimal unitTaxable(Pricing pricing, PurchaseTaxTreatment treatment,
      BigDecimal ratePct) {
    BigDecimal cost = discountedUnitCost(pricing);
    if (cost == null) {
      cost = pricing != null && pricing.getCostPrice() != null
          ? pricing.getCostPrice() : BigDecimal.ZERO;
    }
    if (PurchaseTaxTreatment.orDefault(treatment) == PurchaseTaxTreatment.INCLUSIVE) {
      return GstMath.extractFromInclusive(cost, ratePct).taxable();
    }
    return cost;
  }

  /**
   * Unit cost after the purchase scheme and additional discount, before any GST is taken out.
   *
   * <p>Both kinds of scheme reduce what was charged for the line. A percentage scheme is a straight
   * price cut. A "19+1" deal is the vendor billing nineteen of every twenty units, whether the bill
   * shows it as free goods or as a 5% cut on the quantity, so the line's taxable value is
   * {@code count × cost × 19/20}. The line count is everything received, free units included.
   *
   * <p>This is the landed cost, {@link PricingUtils#computeEffectiveCostPrice}, without its GST
   * step: an inclusive bill has its tax taken out of the whole line by {@link #resolve}. Returns
   * null when there is no cost to reduce.
   */
  private static BigDecimal discountedUnitCost(Pricing pricing) {
    if (pricing == null) {
      return null;
    }
    return PricingUtils.computeEffectiveCostPrice(
        pricing.getCostPrice(), pricing.getPurchaseAdditionalDiscount(),
        pricing.getPurchaseScheme());
  }

  private static BigDecimal rateOf(Pricing pricing) {
    return pricing == null ? BigDecimal.ZERO
        : GstMath.parseGstRate(pricing.getSgst()).add(GstMath.parseGstRate(pricing.getCgst()));
  }

  private static BigDecimal sum(List<BigDecimal> values) {
    return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static BigDecimal money(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP);
  }
}
