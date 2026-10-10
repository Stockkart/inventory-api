package com.inventory.documentservice.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.documentservice.rest.dto.GenerateInvoiceRequest;
import com.inventory.documentservice.rest.dto.InvoiceTaxRateRow;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The dot-matrix bill states tax the way the A4 and thermal invoices do: a row per rate when the
 * bill carries rows, and IGST for an interstate sale.
 */
class InvoiceTextRendererTaxTest {

  private final InvoiceTextRenderer renderer = new InvoiceTextRenderer();

  private static GenerateInvoiceRequest taxBill() {
    GenerateInvoiceRequest request = new GenerateInvoiceRequest();
    request.setDocumentType("SALE");
    request.setBillingMode("REGULAR");
    request.setItems(List.of());
    request.setSubTotal(new BigDecimal("385.55"));
    request.setGrandTotal(new BigDecimal("392.00"));
    // The first line's rate, which the single pair used to be labelled with.
    request.setSgstPercent(new BigDecimal("2.5"));
    request.setCgstPercent(new BigDecimal("2.5"));
    return request;
  }

  @Test
  void mixedRateBillPrintsARowPerRate() {
    // T001312: 298.62 taxable at 5%, 66.51 at 18%.
    GenerateInvoiceRequest request = taxBill();
    request.setTaxRateRows(List.of(
        new InvoiceTaxRateRow(new BigDecimal("2.5"), new BigDecimal("2.5"),
            new BigDecimal("298.62"), new BigDecimal("7.47"), new BigDecimal("7.46")),
        new InvoiceTaxRateRow(new BigDecimal("9"), new BigDecimal("9"),
            new BigDecimal("66.51"), new BigDecimal("5.99"), new BigDecimal("5.98"))));
    request.setSgstAmount(new BigDecimal("13.44"));
    request.setCgstAmount(new BigDecimal("13.46"));

    String text = renderer.render(request);

    assertTrue(text.contains("Add SGST 2.5 %"), text);
    assertTrue(text.contains("Add SGST 9 %"), text);
    assertTrue(text.contains("Add CGST 9 %"), text);
    assertTrue(text.contains("GST=298.62*2.5*2.5%="), text);
    assertTrue(text.contains("GST=66.51*9*9%="), text);
    // The combined 13.44 no longer appears under a single rate.
    assertFalse(text.contains("13.44"), text);
  }

  @Test
  void billWithoutRowsKeepsTheSinglePair() {
    GenerateInvoiceRequest request = taxBill();
    request.setSgstAmount(new BigDecimal("9.64"));
    request.setCgstAmount(new BigDecimal("9.64"));

    String text = renderer.render(request);

    assertTrue(text.contains("Add SGST 2.5 %"), text);
    assertTrue(text.contains("9.64"), text);
  }

  @Test
  void interstateBillPrintsIgst() {
    GenerateInvoiceRequest request = taxBill();
    request.setIgstAmount(new BigDecimal("19.28"));
    request.setIgstPercent(new BigDecimal("5"));

    String text = renderer.render(request);

    assertTrue(text.contains("Add IGST 5 %"), text);
    assertTrue(text.contains("19.28"), text);
    assertFalse(text.contains("Add SGST"), text);
  }

  @Test
  void roundOffIsLabelledByItsDirection() {
    // T001317: 8649.06 after discount plus 871.81 of GST is 9520.87; 0.13 is added to reach 9521.
    GenerateInvoiceRequest up = taxBill();
    up.setRoundOff(new BigDecimal("0.13"));
    assertTrue(renderer.render(up).contains("Add Roundoff"));

    GenerateInvoiceRequest down = taxBill();
    down.setRoundOff(new BigDecimal("-0.40"));
    String text = renderer.render(down);
    assertTrue(text.contains("Less Roundoff"), text);
    assertTrue(text.contains("0.40"), text);
    assertFalse(text.contains("-0.40"), text);
  }
}
