package com.inventory.product.service.creditnote;

import com.inventory.documentservice.rest.dto.CreditNoteItem;
import com.inventory.documentservice.rest.dto.GenerateCreditNoteRequest;
import com.inventory.pluginengine.VerticalFieldsReader;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.ShopInvoiceSettingsDocument;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.service.InvoiceSettingsService;
import com.inventory.product.service.vertical.InventoryVerticalExtensionHandler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared shop chrome + visibility application for credit-note print requests.
 */
@Component
public class CreditNoteRequestSupport {

  /** Expiry is printed as the invoice prints it: month and year on the shop's calendar. */
  private static final DateTimeFormatter EXPIRY_FORMAT =
      DateTimeFormatter.ofPattern("MM/yy").withZone(ZoneId.of("Asia/Kolkata"));

  private final InvoiceSettingsService invoiceSettingsService;
  private final InventoryVerticalExtensionHandler inventoryVerticalExtensionHandler;

  public CreditNoteRequestSupport(
      InvoiceSettingsService invoiceSettingsService,
      InventoryVerticalExtensionHandler inventoryVerticalExtensionHandler) {
    this.invoiceSettingsService = invoiceSettingsService;
    this.inventoryVerticalExtensionHandler = inventoryVerticalExtensionHandler;
  }

  /**
   * Batch and expiry of the lot a note line reverses.
   *
   * <p>They live on the vertical extension, not the lot. A returned batch is how the other party
   * finds the goods on its own books, so a note without it cannot be matched to anything.
   */
  public void applyBatchAndExpiry(CreditNoteItem item, Inventory inventory) {
    if (item == null || inventory == null) {
      return;
    }
    Map<String, Object> fields =
        inventoryVerticalExtensionHandler.loadExtensionFields(
            inventory.getShopId(), inventory.getId());
    item.setBatchNo(VerticalFieldsReader.batchNoFrom(fields));
    Instant expiry = VerticalFieldsReader.expiryDateFrom(fields);
    item.setExpiryDate(expiry != null ? EXPIRY_FORMAT.format(expiry) : null);
  }

  public void applyShopAndVisibility(
      GenerateCreditNoteRequest request,
      Shop shop,
      ShopInvoiceSettingsDocument settings,
      BillingMode billingMode) {
    if (request == null || shop == null) {
      return;
    }
    BillingMode mode = billingMode != null ? billingMode : BillingMode.REGULAR;
    var fields = invoiceSettingsService.fieldsForMode(settings, mode);
    invoiceSettingsService.applyCreditNoteVisibility(request, fields);
    request.setFooterNote(settings != null && settings.getFooterNote() != null ? settings.getFooterNote() : "");

    request.setShopName(shop.getName() != null ? shop.getName() : "");
    if (shop.getLocation() != null) {
      List<String> addressParts = new ArrayList<>();
      if (shop.getLocation().getPrimaryAddress() != null) {
        addressParts.add(shop.getLocation().getPrimaryAddress());
      }
      if (shop.getLocation().getSecondaryAddress() != null) {
        addressParts.add(shop.getLocation().getSecondaryAddress());
      }
      if (shop.getLocation().getCity() != null) {
        addressParts.add(shop.getLocation().getCity());
      }
      if (shop.getLocation().getState() != null) {
        addressParts.add(shop.getLocation().getState());
      }
      if (shop.getLocation().getPin() != null) {
        addressParts.add(shop.getLocation().getPin());
      }
      request.setShopAddress(String.join(", ", addressParts));
      if (shop.getLocation().getState() != null && !shop.getLocation().getState().isEmpty()) {
        request.setPlaceOfSupply(shop.getLocation().getState());
      }
    }
    request.setShopDlNo(shop.getDlNo());
    request.setShopFssai(shop.getFssai());
    request.setShopGstin(shop.getGstinNo());
    request.setShopPhone(shop.getContactPhone());
    request.setShopEmail(shop.getContactEmail());
    request.setShopTagline(shop.getTagline());
    String shopPan = shop.getPanNo();
    if ((shopPan == null || shopPan.isEmpty())
        && shop.getGstinNo() != null
        && shop.getGstinNo().length() >= 12) {
      shopPan = shop.getGstinNo().substring(2, 12);
    }
    request.setShopPan(shopPan);
  }
}
