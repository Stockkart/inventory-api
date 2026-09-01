package com.inventory.product.service.creditnote;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.documentservice.rest.dto.CreditNoteItem;
import com.inventory.documentservice.rest.dto.GenerateCreditNoteRequest;
import com.inventory.pluginengine.VerticalFieldsReader;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.Refund;
import com.inventory.product.domain.model.RefundItem;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.ShopInvoiceSettingsDocument;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.domain.repository.RefundRepository;
import com.inventory.product.service.PurchaseCustomerRequests;
import com.inventory.product.service.vertical.InventoryVerticalExtensionHandler;
import com.inventory.product.utils.AmountToWordsConverter;
import com.inventory.user.domain.model.Customer;
import com.inventory.user.service.CustomerService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Assembles printable credit notes from customer sales returns ({@link Refund}).
 */
@Component
public class CustomerRefundCreditNoteAssembler implements CreditNoteDocumentAssembler {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final RefundRepository refundRepository;
  private final PurchaseRepository purchaseRepository;
  private final InventoryRepository inventoryRepository;
  private final CustomerService customerService;
  private final InventoryVerticalExtensionHandler inventoryVerticalExtensionHandler;
  private final CreditNoteRequestSupport requestSupport;

  public CustomerRefundCreditNoteAssembler(
      RefundRepository refundRepository,
      PurchaseRepository purchaseRepository,
      InventoryRepository inventoryRepository,
      CustomerService customerService,
      InventoryVerticalExtensionHandler inventoryVerticalExtensionHandler,
      CreditNoteRequestSupport requestSupport) {
    this.refundRepository = refundRepository;
    this.purchaseRepository = purchaseRepository;
    this.inventoryRepository = inventoryRepository;
    this.customerService = customerService;
    this.inventoryVerticalExtensionHandler = inventoryVerticalExtensionHandler;
    this.requestSupport = requestSupport;
  }

  @Override
  public CreditNotePartyRole partyRole() {
    return CreditNotePartyRole.CUSTOMER;
  }

  @Override
  public GenerateCreditNoteRequest assemble(
      String documentId, String shopId, Shop shop, ShopInvoiceSettingsDocument settings) {
    Refund refund =
        refundRepository
            .findById(documentId)
            .orElseThrow(() -> new ResourceNotFoundException("Refund", "id", documentId));

    if (!shopId.equals(refund.getShopId())) {
      throw new ValidationException("Refund does not belong to the specified shop");
    }

    Purchase purchase = null;
    if (StringUtils.hasText(refund.getPurchaseId())) {
      purchase = purchaseRepository.findById(refund.getPurchaseId()).orElse(null);
    }

    BillingMode billingMode =
        purchase != null && purchase.getBillingMode() != null
            ? purchase.getBillingMode()
            : BillingMode.REGULAR;

    GenerateCreditNoteRequest request = new GenerateCreditNoteRequest();
    request.setPartyRole(CreditNotePartyRole.CUSTOMER.wireValue());
    requestSupport.applyShopAndVisibility(request, shop, settings, billingMode);

    String noteNo =
        StringUtils.hasText(refund.getCreditNoteNo())
            ? refund.getCreditNoteNo().trim()
            : ("CN-" + refund.getId());
    request.setCreditNoteNo(noteNo);
    if (refund.getCreatedAt() != null) {
      LocalDateTime at = LocalDateTime.ofInstant(refund.getCreatedAt(), IST);
      request.setNoteDate(at.format(DateTimeFormatter.ofPattern("dd-MM-yyyy")));
      request.setNoteTime(at.format(DateTimeFormatter.ofPattern("hh:mm a")));
    }
    if (purchase != null && StringUtils.hasText(purchase.getInvoiceNo())) {
      request.setAgainstInvoiceNo(purchase.getInvoiceNo().trim());
    }
    request.setReason(refund.getReason());
    request.setPaymentMethod(refund.getPaymentMethod());

    populateCustomer(request, refund, purchase);

    List<CreditNoteItem> items = mapItems(refund);
    request.setItems(items);

    BigDecimal taxable = nz(refund.getTaxableTotal());
    BigDecimal cgst = nz(refund.getCgstAmount());
    BigDecimal sgst = nz(refund.getSgstAmount());
    if (taxable.signum() == 0 && !items.isEmpty()) {
      for (CreditNoteItem item : items) {
        taxable = taxable.add(nz(item.getTaxableValue()));
        cgst = cgst.add(nz(item.getCgstAmount()));
        sgst = sgst.add(nz(item.getSgstAmount()));
      }
    }
    BigDecimal grand = nz(refund.getRefundAmount());
    BigDecimal taxTotal = cgst.add(sgst);
    BigDecimal roundOff = nz(refund.getRoundOff());
    if (roundOff.signum() == 0 && taxable.signum() > 0) {
      roundOff = grand.subtract(taxable.add(taxTotal)).setScale(2, RoundingMode.HALF_UP);
    }

    request.setTaxableTotal(taxable.setScale(2, RoundingMode.HALF_UP));
    request.setCgstAmount(cgst.setScale(2, RoundingMode.HALF_UP));
    request.setSgstAmount(sgst.setScale(2, RoundingMode.HALF_UP));
    request.setTaxTotal(taxTotal.setScale(2, RoundingMode.HALF_UP));
    request.setRoundOff(roundOff);
    request.setGrandTotal(grand.setScale(2, RoundingMode.HALF_UP));
    applyTaxPercents(request, items);
    request.setAmountInWords(AmountToWordsConverter.convertAmountToWords(grand));

    return request;
  }

  private void populateCustomer(GenerateCreditNoteRequest request, Refund refund, Purchase purchase) {
    String customerId = refund.getCustomerId();
    if (!StringUtils.hasText(customerId) && purchase != null) {
      customerId = purchase.getCustomerId();
    }
    if (StringUtils.hasText(customerId)) {
      Optional<Customer> customerOpt = customerService.getCustomerById(customerId);
      if (customerOpt.isPresent()) {
        Customer customer = customerOpt.get();
        if (customer.isGeneralCustomer()) {
          String overlay =
              purchase != null
                  ? PurchaseCustomerRequests.sanitizedDisplayName(purchase.getCustomerName())
                  : null;
          request.setPartyName(overlay != null ? overlay : CustomerService.GUEST_CUSTOMER_DISPLAY_NAME);
          return;
        }
        request.setPartyName(customer.getName());
        request.setPartyAddress(customer.getAddress());
        request.setPartyDlNo(customer.getDlNo());
        request.setPartyGstin(customer.getGstin());
        request.setPartyPan(customer.getPan());
        request.setPartyPhone(customer.getPhone());
        request.setPartyEmail(customer.getEmail());
        return;
      }
    }
    if (purchase != null && PurchaseCustomerRequests.sanitizedDisplayName(purchase.getCustomerName()) != null) {
      request.setPartyName(PurchaseCustomerRequests.sanitizedDisplayName(purchase.getCustomerName()));
    } else {
      request.setPartyName(CustomerService.GUEST_CUSTOMER_DISPLAY_NAME);
    }
  }

  private List<CreditNoteItem> mapItems(Refund refund) {
    List<CreditNoteItem> out = new ArrayList<>();
    if (refund.getRefundedItems() == null) {
      return out;
    }
    for (RefundItem line : refund.getRefundedItems()) {
      if (line == null) {
        continue;
      }
      CreditNoteItem item = new CreditNoteItem();
      item.setName(StringUtils.hasText(line.getName()) ? line.getName() : line.getInventoryId());
      item.setQuantity(
          line.getQuantity() != null ? BigDecimal.valueOf(line.getQuantity()) : BigDecimal.ZERO);
      item.setUnitPrice(line.getPriceToRetail());
      BigDecimal lineTotal =
          line.getLineReturnTotal() != null
              ? line.getLineReturnTotal()
              : (line.getItemRefundAmount() != null
                  ? line.getItemRefundAmount()
                  : BigDecimal.ZERO);
      item.setLineTotal(lineTotal);
      item.setTaxableValue(line.getTaxableValue());
      item.setCgstAmount(line.getCgstAmount());
      item.setSgstAmount(line.getSgstAmount());

      if (StringUtils.hasText(line.getInventoryId())) {
        inventoryRepository
            .findById(line.getInventoryId().trim())
            .ifPresent(
                inv -> {
                  item.setHsn(inv.getHsn());
                  item.setCompanyName(inv.getCompanyName());
                  Map<String, Object> extensionFields =
                      inventoryVerticalExtensionHandler.loadExtensionFields(
                          inv.getShopId(), inv.getId());
                  item.setBatchNo(VerticalFieldsReader.batchNoFrom(extensionFields));
                });
      }
      item.setGstPercent(sumAmountsAsPercent(item.getCgst(), item.getSgst()));
      out.add(item);
    }
    return out;
  }

  private static void applyTaxPercents(GenerateCreditNoteRequest request, List<CreditNoteItem> items) {
    request.setCgstPercent(BigDecimal.valueOf(2.5));
    request.setSgstPercent(BigDecimal.valueOf(2.5));
    if (items == null || items.isEmpty()) {
      return;
    }
    CreditNoteItem first = items.get(0);
    if (StringUtils.hasText(first.getCgst())) {
      try {
        request.setCgstPercent(new BigDecimal(first.getCgst().trim()));
      } catch (NumberFormatException ignored) {
        // keep default
      }
    }
    if (StringUtils.hasText(first.getSgst())) {
      try {
        request.setSgstPercent(new BigDecimal(first.getSgst().trim()));
      } catch (NumberFormatException ignored) {
        // keep default
      }
    }
  }

  private static BigDecimal sumAmountsAsPercent(String cgst, String sgst) {
    return parseRate(cgst).add(parseRate(sgst));
  }

  private static BigDecimal parseRate(String rate) {
    if (!StringUtils.hasText(rate)) {
      return BigDecimal.ZERO;
    }
    try {
      return new BigDecimal(rate.trim());
    } catch (NumberFormatException e) {
      return BigDecimal.ZERO;
    }
  }

  private static BigDecimal nz(BigDecimal v) {
    return v != null ? v : BigDecimal.ZERO;
  }
}
