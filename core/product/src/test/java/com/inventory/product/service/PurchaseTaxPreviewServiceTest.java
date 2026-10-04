package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.PurchaseTaxTreatment;
import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.enums.SchemeType;
import com.inventory.product.mapper.InventoryMapperImpl;
import com.inventory.product.rest.dto.request.CreateInventoryItemRequest;
import com.inventory.product.rest.dto.request.PurchaseTaxPreviewRequest;
import com.inventory.product.rest.dto.response.PurchaseTaxPreviewResponse;
import com.inventory.product.validation.VendorPurchaseInvoiceValidator;
import com.inventory.user.domain.model.Vendor;
import com.inventory.user.domain.repository.VendorRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PurchaseTaxPreviewServiceTest {

  private VendorRepository vendorRepository;
  private PurchaseTaxPreviewService service;
  private final List<CreateInventoryItemRequest> items = new ArrayList<>();

  @BeforeEach
  void setUp() {
    vendorRepository = mock(VendorRepository.class);
    when(vendorRepository.findById("v1")).thenReturn(Optional.empty());
    service = new PurchaseTaxPreviewService(
        new InventoryMapperImpl(),
        new PurchaseTaxTreatmentResolver(vendorRepository),
        new VendorPurchaseInvoiceValidator());
  }

  private static void assertMoney(String expected, BigDecimal actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual),
        "expected " + expected + " but was " + actual);
  }

  private CreateInventoryItemRequest item(int count, String cost, String halfGst) {
    CreateInventoryItemRequest item = new CreateInventoryItemRequest();
    item.setCount(count);
    item.setCostPrice(new BigDecimal(cost));
    item.setSgst(halfGst);
    item.setCgst(halfGst);
    items.add(item);
    return item;
  }

  private PurchaseTaxPreviewRequest request(PurchaseTaxTreatment treatment) {
    PurchaseTaxPreviewRequest request = new PurchaseTaxPreviewRequest();
    request.setVendorId("v1");
    request.setTaxTreatment(treatment);
    request.setItems(items);
    return request;
  }

  @Test
  void addsTaxOnTopOfAnExclusiveBill() {
    item(10, "100", "6");
    item(4, "250", "2.5");

    PurchaseTaxPreviewResponse out = service.preview(request(PurchaseTaxTreatment.EXCLUSIVE));

    assertMoney("2000.00", out.getLineSubTotal());
    assertMoney("170.00", out.getTaxTotal());
    assertMoney("2170.00", out.getInvoiceTotal());
    assertMoney("2170.00", out.getItemsTotal());
    assertEquals(2, out.getProductCount());
    assertEquals(14, out.getTotalQuantity());
  }

  @Test
  void takesTaxOutOfAnInclusiveBill() {
    item(1, "112", "6");

    PurchaseTaxPreviewResponse out = service.preview(request(PurchaseTaxTreatment.INCLUSIVE));

    assertMoney("100.00", out.getLineSubTotal());
    assertMoney("12.00", out.getTaxTotal());
  }

  @Test
  void aPercentageSchemeAndAdditionalDiscountReduceTheTaxableValue() {
    CreateInventoryItemRequest row = item(10, "100", "9");
    row.setPurchaseSchemeType(SchemeType.PERCENTAGE);
    row.setPurchaseSchemePercentage(new BigDecimal("10"));
    row.setPurchaseAdditionalDiscount(new BigDecimal("5"));

    PurchaseTaxPreviewResponse out = service.preview(request(PurchaseTaxTreatment.EXCLUSIVE));

    assertMoney("855.00", out.getLineSubTotal());
    assertMoney("153.90", out.getTaxTotal());
  }

  @Test
  void theVendorsDefaultAppliesWhenTheBillSaysNothing() {
    Vendor vendor = new Vendor();
    vendor.setDefaultTaxTreatment(PurchaseTaxTreatment.INCLUSIVE);
    when(vendorRepository.findById("v1")).thenReturn(Optional.of(vendor));
    item(1, "112", "6");

    PurchaseTaxPreviewResponse out = service.preview(request(null));

    assertEquals(PurchaseTaxTreatment.INCLUSIVE, out.getTaxTreatment());
    assertMoney("100.00", out.getLineSubTotal());
  }

  @Test
  void theInvoiceTotalTakesTheDiscountOffBeforeTaxAndAddsTheCharges() {
    item(10, "100", "9");
    PurchaseTaxPreviewRequest request = request(PurchaseTaxTreatment.EXCLUSIVE);
    request.setShippingCharge(new BigDecimal("50"));
    request.setOverallDiscount(new BigDecimal("20"));
    request.setRoundOff(new BigDecimal("0.50"));

    PurchaseTaxPreviewResponse out = service.preview(request);

    // 1000 less 20 = 980 taxable, 176.40 tax, plus 50 shipping and 0.50 round-off.
    assertMoney("176.40", out.getTaxTotal());
    assertMoney("1206.90", out.getInvoiceTotal());
  }

  @Test
  void needsAtLeastOneItem() {
    assertThrows(ValidationException.class,
        () -> service.preview(request(PurchaseTaxTreatment.EXCLUSIVE)));
  }

  @Test
  void thePreviewSaysWhenTheChoiceContradictsTheRows() {
    CreateInventoryItemRequest row = item(50, "108.06", "2.5");
    row.setMaximumRetailPrice(new BigDecimal("160"));

    PurchaseTaxPreviewResponse out = service.preview(request(PurchaseTaxTreatment.INCLUSIVE));

    assertEquals("STATED", out.getTaxTreatmentSource());
    assertEquals(PurchaseTaxTreatment.EXCLUSIVE, out.getTaxTreatmentFromLines());
    assertTrue(out.getTaxTreatmentConflict().startsWith("The entered prices are lower than the MRP."));

    PurchaseTaxPreviewResponse read = service.preview(request(null));
    assertEquals("LINES", read.getTaxTreatmentSource());
    assertEquals(PurchaseTaxTreatment.EXCLUSIVE, read.getTaxTreatment());
    assertEquals(null, read.getTaxTreatmentConflict());
  }
}
