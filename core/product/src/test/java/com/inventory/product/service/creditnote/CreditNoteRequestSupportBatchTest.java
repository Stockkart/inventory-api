package com.inventory.product.service.creditnote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.documentservice.rest.dto.CreditNoteItem;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.service.InvoiceSettingsService;
import com.inventory.product.service.vertical.InventoryVerticalExtensionHandler;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A printed note names the batch it reverses. The vendor debit note printed its batch column
 * empty, and neither note had an expiry at all.
 */
class CreditNoteRequestSupportBatchTest {

  private InventoryVerticalExtensionHandler extensions;
  private CreditNoteRequestSupport support;
  private Inventory lot;

  @BeforeEach
  void setUp() {
    extensions = mock(InventoryVerticalExtensionHandler.class);
    support = new CreditNoteRequestSupport(mock(InvoiceSettingsService.class), extensions);
    lot = new Inventory();
    lot.setId("lot-1");
    lot.setShopId("shop-1");
  }

  @Test
  void theLineCarriesTheLotsBatchAndExpiry() {
    Map<String, Object> fields = new HashMap<>();
    fields.put("batchNo", "SL26075B");
    fields.put("expiryDate", Instant.parse("2027-07-01T00:00:00Z"));
    when(extensions.loadExtensionFields("shop-1", "lot-1")).thenReturn(fields);

    CreditNoteItem item = new CreditNoteItem();
    support.applyBatchAndExpiry(item, lot);

    assertEquals("SL26075B", item.getBatchNo());
    assertEquals("07/27", item.getExpiryDate());
  }

  @Test
  void expiryIsTheMonthOnTheShopsCalendar() {
    // Midnight on 1 August in India is still 31 July in UTC.
    when(extensions.loadExtensionFields("shop-1", "lot-1"))
        .thenReturn(Map.of("expiryDate", Instant.parse("2029-07-31T18:30:00Z")));

    CreditNoteItem item = new CreditNoteItem();
    support.applyBatchAndExpiry(item, lot);

    assertEquals("08/29", item.getExpiryDate());
  }

  @Test
  void aLotWithoutExtensionFieldsLeavesBothEmpty() {
    when(extensions.loadExtensionFields("shop-1", "lot-1")).thenReturn(Map.of());

    CreditNoteItem item = new CreditNoteItem();
    support.applyBatchAndExpiry(item, lot);

    assertNull(item.getBatchNo());
    assertNull(item.getExpiryDate());
  }
}
