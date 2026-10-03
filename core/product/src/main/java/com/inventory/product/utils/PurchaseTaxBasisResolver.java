package com.inventory.product.utils;

import com.inventory.common.util.GstMath;
import com.inventory.common.constants.PurchaseTaxTreatment;
import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.model.Scheme;
import com.inventory.pricing.utils.constants.PricingConstants;
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
   * Unit cost after the price reductions that GST recognises — and only those.
   *
   * <p>Deliberately not {@link com.inventory.pricing.utils.PricingUtils#computeEffectiveCostPrice}, which is the landed cost:
   * it also dilutes the price by free goods, so a "19+1" bonus makes each unit held cost a
   * twentieth less. That is the right basis for valuation and margin, and the wrong one for tax.
   * A supplier who ships twenty and charges for nineteen has charged for nineteen; the taxable
   * value is what was charged, and the free unit does not reduce it.
   *
   * <p>A percentage scheme and an additional discount are genuine reductions in price, so both
   * apply. Returns null when nothing reduces the cost, or when there is no cost to reduce.
   */
  private static BigDecimal discountedUnitCost(Pricing pricing) {
    if (pricing == null || pricing.getCostPrice() == null) {
      return null;
    }
    BigDecimal cost = pricing.getCostPrice();

    Scheme scheme = pricing.getPurchaseScheme();
    if (scheme != null
        && PricingConstants.SCHEME_TYPE_PERCENTAGE.equalsIgnoreCase(scheme.getSchemeType())
        && scheme.getSchemePercentage() != null) {
      BigDecimal pct = scheme.getSchemePercentage();
      if (pct.signum() > 0 && pct.compareTo(BigDecimal.valueOf(100)) < 0) {
        cost = cost.multiply(BigDecimal.ONE.subtract(pct.divide(BigDecimal.valueOf(100))));
      }
    }

    BigDecimal additional = pricing.getPurchaseAdditionalDiscount();
    if (additional != null && additional.signum() != 0
        && additional.compareTo(BigDecimal.valueOf(100)) < 0) {
      cost = cost.multiply(BigDecimal.ONE.subtract(
          additional.divide(BigDecimal.valueOf(100))));
    }
    return cost.setScale(4, RoundingMode.HALF_UP);
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
