package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.repository.PricingRepository;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.model.VendorPurchaseInvoiceLine;
import com.inventory.product.domain.repository.InventoryRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PurchaseTaxRecorderTest {

  private InventoryRepository inventoryRepository;
  private PricingRepository pricingRepository;
  private PurchaseTaxRecorder recorder;
  private final List<Inventory> lots = new ArrayList<>();
  private final List<Pricing> pricings = new ArrayList<>();
  private final List<VendorPurchaseInvoiceLine> lines = new ArrayList<>();

  @BeforeEach
  void setUp() {
    inventoryRepository = mock(InventoryRepository.class);
    pricingRepository = mock(PricingRepository.class);
    when(inventoryRepository.findAllById(anyIterable())).thenReturn(lots);
    when(pricingRepository.findAllById(anyIterable())).thenReturn(pricings);
    recorder = new PurchaseTaxRecorder(inventoryRepository, pricingRepository);
  }

  private void line(int count, String cost, String halfGst) {
    String n = String.valueOf(lines.size());
    Inventory lot = new Inventory();
    lot.setId("inv-" + n);
    lot.setPricingId("pr-" + n);
    lots.add(lot);
    Pricing pricing = new Pricing();
    pricing.setId("pr-" + n);
    pricing.setCostPrice(new BigDecimal(cost));
    pricing.setCgst(halfGst);
    pricing.setSgst(halfGst);
    pricings.add(pricing);
    VendorPurchaseInvoiceLine line = new VendorPurchaseInvoiceLine();
    line.setLineIndex(lines.size());
    line.setCount(count);
    line.setCostPrice(new BigDecimal(cost));
    line.setInventoryId(lot.getId());
    lines.add(line);
  }

  private VendorPurchaseInvoice invoice() {
    VendorPurchaseInvoice invoice = new VendorPurchaseInvoice();
    invoice.setInvoiceNo("A1");
    invoice.setLines(lines);
    return invoice;
  }

  @Test
  void looksUpEveryLineInTwoQueries() {
    line(1, "100", "6");
    line(2, "50", "6");
    line(3, "10", "9");

    assertEquals(3, recorder.pricingByInventoryId(lines).size());

    verify(inventoryRepository, times(1)).findAllById(anyIterable());
    verify(pricingRepository, times(1)).findAllById(anyIterable());
    verify(inventoryRepository, never()).findById(any());
    verify(pricingRepository, never()).findById(any());
  }

  @Test
  void writesTheTotalsTheLinesComeTo() {
    line(10, "100", "9");
    VendorPurchaseInvoice invoice = invoice();
    invoice.setShippingCharge(new BigDecimal("20"));
    invoice.setRoundOff(new BigDecimal("-0.40"));

    recorder.record(invoice);

    assertEquals(0, new BigDecimal("1000.00").compareTo(invoice.getLineSubTotal()));
    assertEquals(0, new BigDecimal("180.00").compareTo(invoice.getTaxTotal()));
    assertEquals(0, new BigDecimal("1199.60").compareTo(invoice.getInvoiceTotal()));
    assertNotNull(lines.get(0).getTaxableValue());
  }

  /** The journal takes the bill-level discount off the subtotal, so the subtotal is before it. */
  @Test
  void theSubtotalIsBeforeTheBillLevelDiscount() {
    line(10, "100", "9");
    VendorPurchaseInvoice invoice = invoice();
    invoice.setOverallDiscount(new BigDecimal("100"));

    recorder.record(invoice);

    assertEquals(0, new BigDecimal("1000.00").compareTo(invoice.getLineSubTotal()));
    assertEquals(0, new BigDecimal("162.00").compareTo(invoice.getTaxTotal()));
    assertEquals(0, new BigDecimal("1062.00").compareTo(invoice.getInvoiceTotal()));
  }

  @Test
  void aLookupFailureNeverStopsTheStockIn() {
    line(1, "100", "9");
    when(inventoryRepository.findAllById(anyIterable())).thenThrow(new IllegalStateException("db"));
    VendorPurchaseInvoice invoice = invoice();

    assertDoesNotThrow(() -> recorder.record(invoice));

    assertNull(invoice.getTaxTotal());
  }
}
