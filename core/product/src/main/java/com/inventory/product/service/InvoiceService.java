package com.inventory.product.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.documentservice.rest.dto.GenerateInvoiceRequest;
import com.inventory.documentservice.rest.dto.InvoiceItem;
import com.inventory.documentservice.service.DocumentService;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.util.AmountToWordsConverter;
import com.inventory.user.domain.model.Customer;
import com.inventory.user.service.CustomerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Service for generating invoices from purchases.
 */
@Service
@Slf4j
@Transactional(readOnly = true)
public class InvoiceService {

  @Autowired
  private PurchaseRepository purchaseRepository;

  @Autowired
  private ShopRepository shopRepository;

  @Autowired
  private InventoryRepository inventoryRepository;

  @Autowired
  private CustomerService customerService;

  @Autowired
  private DocumentService documentService;

  /**
   * Generate invoice PDF for a purchase.
   *
   * @param purchaseId the purchase ID
   * @param shopId the shop ID for validation
   * @return PDF as byte array
   */
  public byte[] generateInvoicePdf(String purchaseId, String shopId) {
    log.info("Generating invoice PDF for purchase: {}, shop: {}", purchaseId, shopId);

    // Get purchase
    Purchase purchase = purchaseRepository.findById(purchaseId)
        .orElseThrow(() -> new ResourceNotFoundException("Purchase", "id", purchaseId));

    // Validate purchase belongs to shop
    if (!shopId.equals(purchase.getShopId())) {
      throw new ValidationException("Purchase does not belong to the specified shop");
    }

    // Get shop
    Shop shop = shopRepository.findById(purchase.getShopId())
        .orElseThrow(() -> new ResourceNotFoundException("Shop", "shopId", purchase.getShopId()));

    // Build GenerateInvoiceRequest
    GenerateInvoiceRequest request = buildGenerateInvoiceRequest(purchase, shop);

    // Generate PDF
    return documentService.generateInvoice(request);
  }

  /**
   * Build GenerateInvoiceRequest from Purchase and Shop.
   */
  private GenerateInvoiceRequest buildGenerateInvoiceRequest(Purchase purchase, Shop shop) {
    GenerateInvoiceRequest request = new GenerateInvoiceRequest();

    // Invoice basic info
    request.setInvoiceNo(purchase.getInvoiceNo() != null ? purchase.getInvoiceNo() : "");
    if (purchase.getSoldAt() != null) {
      LocalDateTime soldAt = LocalDateTime.ofInstant(purchase.getSoldAt(), ZoneId.systemDefault());
      request.setInvoiceDate(soldAt.format(DateTimeFormatter.ofPattern("dd-MM-yyyy")));
      request.setInvoiceTime(soldAt.format(DateTimeFormatter.ofPattern("hh:mm a")));
    }

    // Shop/Seller information
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
      if (shop.getLocation().getPin() != null) {
        addressParts.add(shop.getLocation().getPin());
      }
      request.setShopAddress(String.join(", ", addressParts));
    }
    request.setShopDlNo(shop.getDlNo());
    request.setShopFssai(shop.getFssai());
    request.setShopGstin(shop.getGstinNo());
    request.setShopPhone(shop.getContactPhone());
    request.setShopEmail(shop.getContactEmail());

    // Customer/Buyer information
    if (purchase.getCustomerId() != null && !purchase.getCustomerId().isEmpty()) {
      Optional<Customer> customerOpt = customerService.getCustomerById(purchase.getCustomerId());
      if (customerOpt.isPresent()) {
        Customer customer = customerOpt.get();
        request.setCustomerName(customer.getName());
        request.setCustomerAddress(customer.getAddress());
        request.setCustomerDlNo(customer.getDlNo());
        request.setCustomerGstin(customer.getGstin());
        request.setCustomerPan(customer.getPan());
        request.setCustomerPhone(customer.getPhone());
        request.setCustomerEmail(customer.getEmail());
      }
    } else if (purchase.getCustomerName() != null && !purchase.getCustomerName().isEmpty()) {
      // Use customer name directly if no customer ID
      request.setCustomerName(purchase.getCustomerName());
    }

    // Items - need to fetch inventory details for each item
    List<InvoiceItem> invoiceItems = new ArrayList<>();
    if (purchase.getItems() != null) {
      for (PurchaseItem purchaseItem : purchase.getItems()) {
        InvoiceItem invoiceItem = new InvoiceItem();
        invoiceItem.setQuantity(purchaseItem.getQuantity());
        invoiceItem.setName(purchaseItem.getName());
        invoiceItem.setMaximumRetailPrice(purchaseItem.getMaximumRetailPrice());
        invoiceItem.setSellingPrice(purchaseItem.getSellingPrice());
        invoiceItem.setDiscount(purchaseItem.getDiscount());
        invoiceItem.setInventoryId(purchaseItem.getInventoryId());

        // Get inventory details
        if (purchaseItem.getInventoryId() != null) {
          Optional<Inventory> inventoryOpt = inventoryRepository.findById(purchaseItem.getInventoryId());
          if (inventoryOpt.isPresent()) {
            Inventory inventory = inventoryOpt.get();
            invoiceItem.setHsn(inventory.getHsn());
            invoiceItem.setSac(inventory.getSac());
            invoiceItem.setCompanyName(inventory.getCompanyName());
            invoiceItem.setBatchNo(inventory.getBatchNo());
            invoiceItem.setScheme(inventory.getScheme());
            if (inventory.getExpiryDate() != null) {
              LocalDateTime expiryDateTime = LocalDateTime.ofInstant(inventory.getExpiryDate(), ZoneId.systemDefault());
              invoiceItem.setExpiryDate(expiryDateTime.format(DateTimeFormatter.ofPattern("MM/yy")));
            }
          }
        }

        invoiceItems.add(invoiceItem);
      }
    }
    request.setItems(invoiceItems);

    // Totals and calculations
    request.setSubTotal(purchase.getSubTotal() != null ? purchase.getSubTotal() : BigDecimal.ZERO);
    request.setDiscountTotal(purchase.getDiscountTotal() != null ? purchase.getDiscountTotal() : BigDecimal.ZERO);
    request.setSgstAmount(purchase.getSgstAmount() != null ? purchase.getSgstAmount() : BigDecimal.ZERO);
    request.setCgstAmount(purchase.getCgstAmount() != null ? purchase.getCgstAmount() : BigDecimal.ZERO);
    
    // Calculate tax percentages from amounts
    BigDecimal subTotal = request.getSubTotal();
    if (subTotal != null && subTotal.compareTo(BigDecimal.ZERO) > 0) {
      if (request.getSgstAmount() != null && request.getSgstAmount().compareTo(BigDecimal.ZERO) > 0) {
        BigDecimal sgstPercent = request.getSgstAmount()
            .divide(subTotal, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
        request.setSgstPercent(sgstPercent);
      }
      if (request.getCgstAmount() != null && request.getCgstAmount().compareTo(BigDecimal.ZERO) > 0) {
        BigDecimal cgstPercent = request.getCgstAmount()
            .divide(subTotal, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
        request.setCgstPercent(cgstPercent);
      }
    }
    
    request.setTaxTotal(purchase.getTaxTotal() != null ? purchase.getTaxTotal() : BigDecimal.ZERO);
    
    // Calculate round off
    BigDecimal grandTotal = purchase.getGrandTotal() != null ? purchase.getGrandTotal() : BigDecimal.ZERO;
    BigDecimal calculatedTotal = request.getSubTotal()
        .subtract(request.getDiscountTotal())
        .add(request.getTaxTotal());
    BigDecimal roundOff = grandTotal.subtract(calculatedTotal);
    request.setRoundOff(roundOff);
    request.setGrandTotal(grandTotal);

    // Additional fields
    request.setPaymentMethod(purchase.getPaymentMethod());
    request.setAmountInWords(AmountToWordsConverter.convertAmountToWords(grandTotal));
    request.setFooterNote(""); // Can be configured later

    // Legacy fields
    request.setSoldAt(purchase.getSoldAt());

    return request;
  }
}

