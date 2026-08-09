package com.inventory.product.service.creditnote;

import com.inventory.documentservice.rest.dto.GenerateCreditNoteRequest;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.ShopInvoiceSettingsDocument;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.service.InvoiceSettingsService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared shop chrome + visibility application for credit-note print requests.
 */
@Component
public class CreditNoteRequestSupport {

  private final InvoiceSettingsService invoiceSettingsService;

  public CreditNoteRequestSupport(InvoiceSettingsService invoiceSettingsService) {
    this.invoiceSettingsService = invoiceSettingsService;
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
