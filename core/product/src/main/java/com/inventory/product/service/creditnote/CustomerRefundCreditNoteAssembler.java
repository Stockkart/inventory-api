package com.inventory.product.service.creditnote;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.documentservice.rest.dto.CreditNoteItem;
import com.inventory.product.domain.model.enums.SchemeType;
import com.inventory.documentservice.rest.dto.GenerateCreditNoteRequest;
import com.inventory.product.domain.model.Inventory;
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
import com.inventory.product.utils.AmountToWordsConverter;
import com.inventory.product.utils.CreditNoteFigures;
import com.inventory.user.domain.model.Customer;
import com.inventory.user.service.CustomerService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
  private final CreditNoteRequestSupport requestSupport;

  public CustomerRefundCreditNoteAssembler(
      RefundRepository refundRepository,
      PurchaseRepository purchaseRepository,
      InventoryRepository inventoryRepository,
      CustomerService customerService,
      CreditNoteRequestSupport requestSupport) {
    this.refundRepository = refundRepository;
    this.purchaseRepository = purchaseRepository;
    this.inventoryRepository = inventoryRepository;
    this.customerService = customerService;
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

    CreditNoteFigures.Totals totals = CreditNoteFigures.totals(refund, refund.getRefundedItems());
    request.setTaxableTotal(totals.taxableTotal());
    request.setCgstAmount(totals.cgstAmount());
    request.setSgstAmount(totals.sgstAmount());
    // A return of an interstate sale reverses IGST, and the note states it under that head.
    request.setIgstAmount(totals.igstAmount());
    request.setInterstate(Boolean.TRUE.equals(refund.getInterstate()));
    request.setTaxTotal(totals.taxTotal());
    request.setRoundOff(totals.roundOff());
    request.setGrandTotal(totals.grandTotal());
    applyTaxPercents(request, items);
    request.setAmountInWords(AmountToWordsConverter.convertAmountToWords(totals.grandTotal()));

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
    // The note's lots and their vertical fields, read once for the note rather than per line
    Set<String> lotIds = new LinkedHashSet<>();
    for (RefundItem line : refund.getRefundedItems()) {
      if (line != null && StringUtils.hasText(line.getInventoryId())) {
        lotIds.add(line.getInventoryId().trim());
      }
    }
    Map<String, Inventory> lots = new HashMap<>();
    if (!lotIds.isEmpty()) {
      for (Inventory inv : inventoryRepository.findAllById(lotIds)) {
        if (inv != null && inv.getId() != null) {
          lots.put(inv.getId(), inv);
        }
      }
    }
    Map<String, Map<String, Object>> fieldsByLot = requestSupport.extensionFields(lots.values());

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
      // Restate the sale in its own terms. Snapshotted onto the refund when it was taken, so a
      // note printed years later still shows what the customer was actually billed.
      item.setMaximumRetailPrice(line.getMaximumRetailPrice());
      item.setDiscountPercent(line.getSaleAdditionalDiscount());
      item.setSchemeLabel(
          saleSchemeLabel(
              line.getSchemeType(), line.getSchemePercentage(),
              line.getSchemePayFor(), line.getSchemeFree()));
      item.setCgst(line.getCgst());
      item.setSgst(line.getSgst());

      if (StringUtils.hasText(line.getInventoryId())) {
        Inventory inv = lots.get(line.getInventoryId().trim());
        if (inv != null) {
          item.setHsn(inv.getHsn());
          item.setCompanyName(inv.getCompanyName());
          requestSupport.applyBatchAndExpiry(item, fieldsByLot.getOrDefault(inv.getId(), Map.of()));
        }
      }
      item.setGstPercent(CreditNoteFigures.lineRatePct(item.getCgst(), item.getSgst()));
      out.add(item);
    }
    return out;
  }

  /**
   * The sale scheme as billed, worded for print.
   *
   * <p>A percentage, or a pay-for/free pair, or nothing. Worded here rather than in the template
   * because a debit note reads the purchase-side fields for the same column, and a template
   * choosing between them would have to know which document it is rendering.
   */
  private static String saleSchemeLabel(
      SchemeType schemeType, BigDecimal percentage, Integer payFor, Integer free) {
    if (schemeType == SchemeType.PERCENTAGE && percentage != null
        && percentage.signum() > 0) {
      return percentage.stripTrailingZeros().toPlainString() + "%";
    }
    if (payFor != null && free != null) {
      return payFor + "+" + free;
    }
    return null;
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

}
