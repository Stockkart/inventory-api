package com.inventory.product.service.creditnote;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.documentservice.rest.dto.CreditNoteItem;
import com.inventory.documentservice.rest.dto.GenerateCreditNoteRequest;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.ShopInvoiceSettingsDocument;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.model.VendorPurchaseInvoiceLine;
import com.inventory.product.domain.model.VendorPurchaseReturn;
import com.inventory.product.domain.model.VendorPurchaseReturnItem;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.VendorPurchaseInvoiceRepository;
import com.inventory.product.domain.repository.VendorPurchaseReturnRepository;
import com.inventory.product.utils.AmountToWordsConverter;
import com.inventory.user.domain.model.Vendor;
import com.inventory.user.domain.repository.VendorRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assembles printable credit notes from vendor purchase returns.
 */
@Component
public class VendorReturnCreditNoteAssembler implements CreditNoteDocumentAssembler {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final VendorPurchaseReturnRepository vendorPurchaseReturnRepository;
  private final VendorPurchaseInvoiceRepository vendorPurchaseInvoiceRepository;
  private final VendorRepository vendorRepository;
  private final InventoryRepository inventoryRepository;
  private final CreditNoteRequestSupport requestSupport;

  public VendorReturnCreditNoteAssembler(
      VendorPurchaseReturnRepository vendorPurchaseReturnRepository,
      VendorPurchaseInvoiceRepository vendorPurchaseInvoiceRepository,
      VendorRepository vendorRepository,
      InventoryRepository inventoryRepository,
      CreditNoteRequestSupport requestSupport) {
    this.vendorPurchaseReturnRepository = vendorPurchaseReturnRepository;
    this.vendorPurchaseInvoiceRepository = vendorPurchaseInvoiceRepository;
    this.vendorRepository = vendorRepository;
    this.inventoryRepository = inventoryRepository;
    this.requestSupport = requestSupport;
  }

  @Override
  public CreditNotePartyRole partyRole() {
    return CreditNotePartyRole.VENDOR;
  }

  @Override
  public GenerateCreditNoteRequest assemble(
      String documentId, String shopId, Shop shop, ShopInvoiceSettingsDocument settings) {
    VendorPurchaseReturn record =
        vendorPurchaseReturnRepository
            .findById(documentId)
            .orElseThrow(
                () -> new ResourceNotFoundException("VendorPurchaseReturn", "id", documentId));

    if (!shopId.equals(record.getShopId())) {
      throw new ValidationException("Vendor purchase return does not belong to the specified shop");
    }

    VendorPurchaseInvoice invoice = null;
    if (StringUtils.hasText(record.getVendorPurchaseInvoiceId())) {
      invoice =
          vendorPurchaseInvoiceRepository
              .findById(record.getVendorPurchaseInvoiceId().trim())
              .orElse(null);
    }

    GenerateCreditNoteRequest request = new GenerateCreditNoteRequest();
    request.setPartyRole(CreditNotePartyRole.VENDOR.wireValue());
    requestSupport.applyShopAndVisibility(request, shop, settings, BillingMode.REGULAR);

    String noteNo =
        StringUtils.hasText(record.getSupplierCreditNoteNo())
            ? record.getSupplierCreditNoteNo().trim()
            : ("VCN-" + record.getId());
    request.setCreditNoteNo(noteNo);
    if (record.getCreatedAt() != null) {
      LocalDateTime at = LocalDateTime.ofInstant(record.getCreatedAt(), IST);
      request.setNoteDate(at.format(DateTimeFormatter.ofPattern("dd-MM-yyyy")));
      request.setNoteTime(at.format(DateTimeFormatter.ofPattern("hh:mm a")));
    }
    if (invoice != null && StringUtils.hasText(invoice.getInvoiceNo())) {
      request.setAgainstInvoiceNo(invoice.getInvoiceNo().trim());
    }
    request.setReason(record.getReason());
    request.setPaymentMethod(record.getPaymentMethod());

    populateVendor(request, invoice);

    List<CreditNoteItem> items = mapItems(record, invoice);
    request.setItems(items);

    BigDecimal taxable = BigDecimal.ZERO;
    BigDecimal cgst = BigDecimal.ZERO;
    BigDecimal sgst = BigDecimal.ZERO;
    for (CreditNoteItem item : items) {
      taxable = taxable.add(nz(item.getTaxableValue()));
      cgst = cgst.add(nz(item.getCgstAmount()));
      sgst = sgst.add(nz(item.getSgstAmount()));
    }
    BigDecimal grand = nz(record.getReturnAmount());
    BigDecimal taxTotal = cgst.add(sgst);
    BigDecimal preRound = taxable.add(taxTotal).setScale(2, RoundingMode.HALF_UP);
    BigDecimal roundOff = grand.subtract(preRound).setScale(2, RoundingMode.HALF_UP);

    request.setTaxableTotal(taxable.setScale(2, RoundingMode.HALF_UP));
    request.setCgstAmount(cgst.setScale(2, RoundingMode.HALF_UP));
    request.setSgstAmount(sgst.setScale(2, RoundingMode.HALF_UP));
    request.setTaxTotal(taxTotal.setScale(2, RoundingMode.HALF_UP));
    request.setRoundOff(roundOff);
    request.setGrandTotal(grand.setScale(2, RoundingMode.HALF_UP));
    request.setCgstPercent(BigDecimal.valueOf(2.5));
    request.setSgstPercent(BigDecimal.valueOf(2.5));
    request.setAmountInWords(AmountToWordsConverter.convertAmountToWords(grand));

    return request;
  }

  private void populateVendor(GenerateCreditNoteRequest request, VendorPurchaseInvoice invoice) {
    if (invoice == null || !StringUtils.hasText(invoice.getVendorId())) {
      request.setPartyName("Vendor");
      return;
    }
    Vendor vendor = vendorRepository.findById(invoice.getVendorId().trim()).orElse(null);
    if (vendor == null) {
      request.setPartyName("Vendor");
      return;
    }
    request.setPartyName(StringUtils.hasText(vendor.getName()) ? vendor.getName().trim() : "Vendor");
    request.setPartyAddress(vendor.getAddress());
    request.setPartyGstin(vendor.getGstinUin());
    request.setPartyPhone(vendor.getContactPhone());
    request.setPartyEmail(vendor.getContactEmail());
  }

  private List<CreditNoteItem> mapItems(VendorPurchaseReturn record, VendorPurchaseInvoice invoice) {
    List<CreditNoteItem> out = new ArrayList<>();
    if (record.getItems() == null || record.getItems().isEmpty()) {
      return out;
    }

    Set<String> inventoryIds = new HashSet<>();
    for (VendorPurchaseReturnItem line : record.getItems()) {
      if (line != null && StringUtils.hasText(line.getInventoryId())) {
        inventoryIds.add(line.getInventoryId().trim());
      }
    }
    Map<String, Inventory> inventoryById = new HashMap<>();
    if (!inventoryIds.isEmpty()) {
      for (Inventory inv : inventoryRepository.findAllById(inventoryIds)) {
        if (inv != null && StringUtils.hasText(inv.getId())) {
          inventoryById.put(inv.getId().trim(), inv);
        }
      }
    }

    Map<String, VendorPurchaseInvoiceLine> invoiceLineByInventoryId = new HashMap<>();
    if (invoice != null && invoice.getLines() != null) {
      for (VendorPurchaseInvoiceLine line : invoice.getLines()) {
        if (line != null && StringUtils.hasText(line.getInventoryId())) {
          invoiceLineByInventoryId.putIfAbsent(line.getInventoryId().trim(), line);
        }
      }
    }

    for (VendorPurchaseReturnItem line : record.getItems()) {
      if (line == null) {
        continue;
      }
      CreditNoteItem item = new CreditNoteItem();
      String invId = StringUtils.hasText(line.getInventoryId()) ? line.getInventoryId().trim() : null;
      Inventory inventory = invId != null ? inventoryById.get(invId) : null;
      VendorPurchaseInvoiceLine invoiceLine =
          invId != null ? invoiceLineByInventoryId.get(invId) : null;

      String name = null;
      if (inventory != null && StringUtils.hasText(inventory.getName())) {
        name = inventory.getName().trim();
      } else if (invoiceLine != null && StringUtils.hasText(invoiceLine.getName())) {
        name = invoiceLine.getName().trim();
      } else {
        name = invId != null ? invId : "Item";
      }
      item.setName(name);
      item.setQuantity(
          line.getBaseQuantityReturned() != null
              ? BigDecimal.valueOf(line.getBaseQuantityReturned())
              : BigDecimal.ZERO);
      item.setTaxableValue(line.getTaxableValue());
      item.setCgstAmount(line.getCentralTaxAmount());
      item.setSgstAmount(line.getStateUtTaxAmount());
      item.setLineTotal(
          line.getLineNoteValue() != null
              ? line.getLineNoteValue()
              : nz(line.getTaxableValue())
                  .add(nz(line.getCentralTaxAmount()))
                  .add(nz(line.getStateUtTaxAmount())));
      if (inventory != null) {
        item.setHsn(inventory.getHsn());
        item.setCompanyName(inventory.getCompanyName());
      }
      if (item.getQuantity().signum() > 0 && item.getLineTotal() != null) {
        item.setUnitPrice(
            item.getLineTotal().divide(item.getQuantity(), 2, RoundingMode.HALF_UP));
      }
      out.add(item);
    }
    return out;
  }

  private static BigDecimal nz(BigDecimal v) {
    return v != null ? v : BigDecimal.ZERO;
  }
}
