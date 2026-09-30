package com.inventory.product.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.model.Scheme;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.model.VendorPurchaseInvoiceLine;
import com.inventory.product.domain.model.enums.PurchaseTaxTreatment;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PurchaseTaxBasisResolverTest {

  private final Map<String, Pricing> pricing = new HashMap<>();
  private final List<VendorPurchaseInvoiceLine> lines = new ArrayList<>();

  private static BigDecimal bd(String value) {
    return new BigDecimal(value);
  }

  private static void assertMoney(String expected, BigDecimal actual) {
    assertEquals(0, bd(expected).compareTo(actual), "expected " + expected + " but was " + actual);
  }

  /** A line of {@code count} at list {@code cost}, taxed at {@code halfGst} each side. */
  private void line(int count, String cost, String halfGst, String schemePct, String additional) {
    String inventoryId = "inv-" + lines.size();
    VendorPurchaseInvoiceLine line = new VendorPurchaseInvoiceLine();
    line.setLineIndex(lines.size());
    line.setCount(count);
    line.setCostPrice(bd(cost));
    line.setInventoryId(inventoryId);
    lines.add(line);

    Pricing p = new Pricing();
    p.setCostPrice(bd(cost));
    p.setCgst(halfGst);
    p.setSgst(halfGst);
    if (schemePct != null) {
      Scheme scheme = new Scheme();
      scheme.setSchemeType("PERCENTAGE");
      scheme.setSchemePercentage(bd(schemePct));
      p.setPurchaseScheme(scheme);
    }
    if (additional != null) {
      p.setPurchaseAdditionalDiscount(bd(additional));
    }
    pricing.put(inventoryId, p);
  }

  private PurchaseTaxBasis resolve(
      String subTotal, String tax, PurchaseTaxTreatment treatment, boolean interstate) {
    VendorPurchaseInvoice invoice = new VendorPurchaseInvoice();
    invoice.setLineSubTotal(subTotal == null ? null : bd(subTotal));
    invoice.setTaxTotal(tax == null ? null : bd(tax));
    invoice.setLines(lines);
    return PurchaseTaxBasisResolver.resolve(invoice, pricing::get, treatment, interstate);
  }

  private static BigDecimal central(PurchaseTaxBasis basis) {
    return basis.lines().stream().map(PurchaseTaxBasis.Line::centralTax)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static BigDecimal state(PurchaseTaxBasis basis) {
    return basis.lines().stream().map(PurchaseTaxBasis.Line::stateTax)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  @Test
  void consistentHeaderIsUsedAsStatedTaxIncluded() {
    line(10, "100", "2.5", null, null);
    line(3, "333.33", "6", null, null);

    // Lines imply 50.00 + 119.99 = 169.99; the bill prints 170.00 -- a paisa of rounding.
    PurchaseTaxBasis basis = resolve("1999.99", "170.00", PurchaseTaxTreatment.EXCLUSIVE, false);

    assertEquals(PurchaseTaxBasis.Verdict.OK, basis.verdict());
    assertMoney("1999.99", basis.totalTaxable());
    assertMoney("170.00", basis.totalTax());
    assertMoney("85.00", central(basis));
    assertMoney("85.00", state(basis));
    assertEquals(PurchaseTaxBasis.Source.HEADER_CONSISTENT, basis.lines().get(0).source());
  }

  @Test
  void inclusiveBillDoesNotLoseTaxTwiceFromItsHeader() {
    line(10, "105", "2.5", null, null);

    // The printed header is ex-tax even on an MRP-billed invoice: 1000.00 + 50.00.
    PurchaseTaxBasis exclusive = resolve("1000.00", "50.00", PurchaseTaxTreatment.EXCLUSIVE, false);
    PurchaseTaxBasis inclusive = resolve("1000.00", "50.00", PurchaseTaxTreatment.INCLUSIVE, false);

    assertEquals(PurchaseTaxBasis.Verdict.OK, inclusive.verdict());
    assertMoney("1000.00", inclusive.totalTaxable());
    assertMoney("50.00", inclusive.totalTax());
    assertEquals(PurchaseTaxBasis.Source.HEADER_CONSISTENT, inclusive.lines().get(0).source());
    assertMoney(exclusive.totalTaxable().toPlainString(), inclusive.totalTaxable());
  }

  @Test
  void singleRateHeaderThatDisagreesIsReadFromItsTax() {
    line(10, "100", "2.5", null, null);

    // Gross keyed as subtotal: 1050 and 50 do not agree at 5%, so the tax is trusted.
    for (PurchaseTaxTreatment treatment : PurchaseTaxTreatment.values()) {
      PurchaseTaxBasis basis = resolve("1050.00", "50.00", treatment, false);
      assertEquals(PurchaseTaxBasis.Verdict.MISMATCH, basis.verdict());
      assertEquals(PurchaseTaxBasis.Source.DERIVED_FROM_TAX, basis.lines().get(0).source());
      assertMoney("1000.00", basis.totalTaxable());
      assertMoney("50.00", basis.totalTax());
    }
  }

  @Test
  void headerImplyingASlabNoLineCarriesIsARateConflict() {
    line(10, "100", "2.5", null, null);

    PurchaseTaxBasis basis = resolve("1000.00", "120.00", PurchaseTaxTreatment.EXCLUSIVE, false);

    assertEquals(PurchaseTaxBasis.Verdict.RATE_CONFLICT, basis.verdict());
  }

  @Test
  void withoutAHeaderTheLandedLineValueIsTheBasis() {
    line(10, "100", "2.5", "5", "10");

    PurchaseTaxBasis basis = resolve(null, null, PurchaseTaxTreatment.EXCLUSIVE, false);

    assertEquals(PurchaseTaxBasis.Verdict.MISSING, basis.verdict());
    assertEquals(PurchaseTaxBasis.Source.LINE_LANDED, basis.lines().get(0).source());
    // 100 less 5% scheme less 10% discount = 85.50 a unit.
    assertMoney("855.00", basis.totalTaxable());
    assertMoney("42.76", basis.totalTax());
  }

  @Test
  void inclusiveLineValuesHaveTheirTaxTakenOut() {
    line(10, "105", "2.5", null, null);

    PurchaseTaxBasis basis = resolve(null, null, PurchaseTaxTreatment.INCLUSIVE, false);

    assertEquals(PurchaseTaxBasis.Verdict.MISSING, basis.verdict());
    assertEquals(PurchaseTaxBasis.Source.INCLUSIVE_EXTRACTED, basis.lines().get(0).source());
    assertMoney("1000.00", basis.totalTaxable());
    assertMoney("50.00", basis.totalTax());
  }

  @Test
  void withNothingButListPricesTheGrossIsTheBasis() {
    line(4, "250", "9", null, null);

    PurchaseTaxBasis basis = resolve(null, null, PurchaseTaxTreatment.EXCLUSIVE, false);

    assertEquals(PurchaseTaxBasis.Source.LINE_GROSS, basis.lines().get(0).source());
    assertMoney("1000.00", basis.totalTaxable());
    assertMoney("180.00", basis.totalTax());
  }

  @Test
  void interstateSupplyIsAllIntegratedTax() {
    line(10, "100", "2.5", null, null);

    PurchaseTaxBasis basis = resolve("1000.00", "50.00", PurchaseTaxTreatment.EXCLUSIVE, true);

    assertEquals(PurchaseTaxBasis.Verdict.OK, basis.verdict());
    assertMoney("50.00", basis.lines().get(0).integratedTax());
    assertMoney("0", central(basis));
    assertMoney("0", state(basis));
  }

  @Test
  void toleranceIsMeasuredAgainstTheTaxNotTheSubtotal() {
    line(1000, "100", "2.5", null, null);

    // Lines imply 5000.00. 0.5% of the stated tax allows about 25.
    PurchaseTaxBasis rounding = resolve("100000.00", "5020.00", PurchaseTaxTreatment.EXCLUSIVE,
        false);
    assertEquals(PurchaseTaxBasis.Verdict.OK, rounding.verdict());

    // A 2% gap is a missing discount, not rounding. 0.5% of the subtotal (500) used to accept it.
    PurchaseTaxBasis discount = resolve("100000.00", "5100.00", PurchaseTaxTreatment.EXCLUSIVE,
        false);
    assertNotEquals(PurchaseTaxBasis.Verdict.OK, discount.verdict());
  }

  @Test
  void aSmallBillStillGetsARupeeOfRoundingRoom() {
    line(1, "20", "2.5", null, null);

    PurchaseTaxBasis basis = resolve("20.00", "1.90", PurchaseTaxTreatment.EXCLUSIVE, false);

    assertEquals(PurchaseTaxBasis.Verdict.OK, basis.verdict());
  }

  @Test
  void aZeroRatedLineNeverHoldsTheRoundingPaisa() {
    line(3, "33.33", "9", null, null);
    line(1, "50", "0", null, null);

    PurchaseTaxBasis basis = resolve("149.99", "18.01", PurchaseTaxTreatment.EXCLUSIVE, false);

    assertEquals(PurchaseTaxBasis.Verdict.OK, basis.verdict());
    assertMoney("0", basis.lines().get(1).tax());
    assertMoney("18.01", basis.totalTax());
  }
}
