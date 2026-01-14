package com.inventory.product.rest.mapper;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.PurchaseStatus;
import com.inventory.product.rest.dto.sale.AddToCartRequest;
import com.inventory.product.rest.dto.sale.AddToCartResponse;
import com.inventory.product.rest.dto.sale.CheckoutRequest;
import com.inventory.product.rest.dto.sale.CheckoutResponse;
import com.inventory.product.rest.dto.sale.PurchaseSummaryDto;
import com.inventory.product.rest.dto.sale.SaleStatusResponse;
import com.inventory.user.service.CustomerService;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Mapper(componentModel = "spring", imports = {Instant.class, BigDecimal.class, PurchaseStatus.class}, unmappedTargetPolicy = ReportingPolicy.IGNORE)
public abstract class PurchaseMapper {
  
  @Autowired
  protected CustomerService customerService;


  @Mapping(target = "saleId", source = "id")
  @Mapping(target = "valid", source = "valid")
  public abstract SaleStatusResponse toStatusResponse(Purchase purchase);

  // New mapping methods for creating Purchase and PurchaseItem

  // MongoDB will auto-generate the id as ObjectId
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "invoiceId", expression = "java(java.util.UUID.randomUUID().toString())")
  @Mapping(target = "soldAt", expression = "java(Instant.now())")
  @Mapping(target = "valid", constant = "true")
  @Mapping(target = "items", source = "purchaseItems")
  @Mapping(target = "subTotal", source = "subTotal")
  @Mapping(target = "taxTotal", source = "taxTotal")
  @Mapping(target = "discountTotal", source = "discountTotal")
  @Mapping(target = "grandTotal", source = "grandTotal")
  @Mapping(target = "paymentMethod", source = "request.paymentMethod")
  @Mapping(target = "businessType", ignore = true)
  @Mapping(target = "invoiceNo", ignore = true)
  @Mapping(target = "shopId", ignore = true)
  @Mapping(target = "userId", ignore = true)
  @Mapping(target = "status", ignore = true)
  public abstract Purchase toPurchase(CheckoutRequest request, List<PurchaseItem> purchaseItems,
                      BigDecimal subTotal, BigDecimal taxTotal,
                      BigDecimal discountTotal, BigDecimal grandTotal);

  @Mapping(target = "inventoryId", source = "item.id")
  @Mapping(target = "name", source = "inventory.name")
  @Mapping(target = "quantity", source = "item.quantity")
  @Mapping(target = "maximumRetailPrice", source = "inventory.maximumRetailPrice")
  @Mapping(target = "sellingPrice", source = "item.sellingPrice")
  @Mapping(target = "discount", expression = "java(calculateDiscount(inventory.getMaximumRetailPrice(), item.getSellingPrice()))")
  @Mapping(target = "sgst", source = "inventory.sgst")
  @Mapping(target = "cgst", source = "inventory.cgst")
  public abstract PurchaseItem toPurchaseItem(CheckoutRequest.CheckoutItem item, Inventory inventory);

  @Mapping(target = "inventoryId", source = "item.id")
  @Mapping(target = "name", source = "inventory.name")
  @Mapping(target = "quantity", source = "item.quantity")
  @Mapping(target = "maximumRetailPrice", source = "inventory.maximumRetailPrice")
  @Mapping(target = "sellingPrice", source = "item.sellingPrice")
  @Mapping(target = "discount", expression = "java(calculateDiscount(inventory.getMaximumRetailPrice(), item.getSellingPrice()))")
  @Mapping(target = "sgst", source = "inventory.sgst")
  @Mapping(target = "cgst", source = "inventory.cgst")
  public abstract PurchaseItem toPurchaseItemFromCartItem(AddToCartRequest.CartItem item, Inventory inventory);

  // Helper method to calculate discount: maximumRetailPrice - sellingPrice
  protected BigDecimal calculateDiscount(BigDecimal maximumRetailPrice, BigDecimal sellingPrice) {
    if (maximumRetailPrice == null || sellingPrice == null) {
      return BigDecimal.ZERO;
    }
    BigDecimal discount = maximumRetailPrice.subtract(sellingPrice);
    return discount.compareTo(BigDecimal.ZERO) > 0 ? discount : BigDecimal.ZERO;
  }

  // Helper method to generate invoice number (moved from service)
  protected String generateInvoiceNo() {
    String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    String random = String.format("%04d", (int) (Math.random() * 10_000));
    return "INV-" + timestamp + "-" + random;
  }

  // Methods to create PurchaseItem
  public PurchaseItem createPurchaseItem(String inventoryId, String name, Integer quantity,
                                          BigDecimal maximumRetailPrice, BigDecimal sellingPrice, BigDecimal discount) {
    PurchaseItem item = new PurchaseItem();
    item.setInventoryId(inventoryId);
    item.setName(name);
    item.setQuantity(quantity);
    item.setMaximumRetailPrice(maximumRetailPrice);
    item.setSellingPrice(sellingPrice);
    item.setDiscount(discount);
    // Note: CGST/SGST not set here as this method is used for negative quantities
    // For normal items, use toPurchaseItemFromCartItem which includes CGST/SGST from inventory
    return item;
  }

  // Method to create Purchase for cart
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "invoiceId", expression = "java(java.util.UUID.randomUUID().toString())")
  @Mapping(target = "invoiceNo", expression = "java(generateInvoiceNo())")
  @Mapping(target = "businessType", source = "request.businessType")
  @Mapping(target = "userId", source = "userId")
  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "items", source = "purchaseItems")
  @Mapping(target = "subTotal", source = "subTotal")
  @Mapping(target = "taxTotal", source = "taxTotal")
  @Mapping(target = "discountTotal", source = "discountTotal")
  @Mapping(target = "grandTotal", source = "grandTotal")
  @Mapping(target = "soldAt", expression = "java(Instant.now())")
  @Mapping(target = "valid", constant = "true")
  @Mapping(target = "status", expression = "java(PurchaseStatus.CREATED)")
  @Mapping(target = "paymentMethod", ignore = true)
  @Mapping(target = "customerId", source = "customerId")
  @Mapping(target = "createdAt", expression = "java(Instant.now())")
  @Mapping(target = "updatedAt", expression = "java(Instant.now())")
  public abstract Purchase toPurchaseForCart(AddToCartRequest request, List<PurchaseItem> purchaseItems,
                             BigDecimal subTotal, BigDecimal taxTotal,
                             BigDecimal discountTotal, BigDecimal grandTotal,
                             String shopId, String userId, String customerId);

  // Method to map Purchase to AddToCartResponse
  @Mapping(target = "purchaseId", source = "id")
  @Mapping(target = "invoiceId", source = "invoiceId")
  @Mapping(target = "invoiceNo", source = "invoiceNo")
  @Mapping(target = "businessType", source = "businessType")
  @Mapping(target = "userId", source = "userId")
  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "items", expression = "java(purchase.getItems() != null ? purchase.getItems() : java.util.List.of())")
  @Mapping(target = "subTotal", source = "subTotal")
  @Mapping(target = "taxTotal", source = "taxTotal")
  @Mapping(target = "sgstAmount", source = "sgstAmount")
  @Mapping(target = "cgstAmount", source = "cgstAmount")
  @Mapping(target = "discountTotal", source = "discountTotal")
  @Mapping(target = "grandTotal", source = "grandTotal")
  @Mapping(target = "status", source = "status")
  @Mapping(target = "customerId", source = "customerId")
  @Mapping(target = "customerName", ignore = true)
  @Mapping(target = "customerAddress", ignore = true)
  @Mapping(target = "customerPhone", ignore = true)
  @Mapping(target = "paymentMethod", source = "paymentMethod")
  public abstract AddToCartResponse toAddToCartResponse(Purchase purchase);

  @AfterMapping
  protected void populateCustomerDetails(@MappingTarget AddToCartResponse response, Purchase purchase) {
    // If customerId exists, fetch customer details
    if (purchase.getCustomerId() != null && !purchase.getCustomerId().trim().isEmpty()) {
      customerService.getCustomerById(purchase.getCustomerId()).ifPresent(customer -> {
        response.setCustomerName(customer.getName());
        response.setCustomerAddress(customer.getAddress());
        response.setCustomerPhone(customer.getPhone());
        response.setCustomerGstin(customer.getGstin());
        response.setCustomerDlNo(customer.getDlNo());
        response.setCustomerPan(customer.getPan());
      });
    } else if (purchase.getCustomerName() != null && !purchase.getCustomerName().trim().isEmpty()) {
      // If only customerName is stored (no customerId), use it directly
      response.setCustomerName(purchase.getCustomerName());
    }
  }

  // Method to map Purchase to CheckoutResponse
  @Mapping(target = "invoiceId", source = "invoiceId")
  @Mapping(target = "invoiceNo", source = "invoiceNo")
  @Mapping(target = "businessType", source = "businessType")
  @Mapping(target = "userId", source = "userId")
  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "items", expression = "java(purchase.getItems() != null ? purchase.getItems() : java.util.List.of())")
  @Mapping(target = "subTotal", source = "subTotal")
  @Mapping(target = "taxTotal", source = "taxTotal")
  @Mapping(target = "sgstAmount", source = "sgstAmount")
  @Mapping(target = "cgstAmount", source = "cgstAmount")
  @Mapping(target = "discountTotal", source = "discountTotal")
  @Mapping(target = "grandTotal", source = "grandTotal")
  @Mapping(target = "paymentMethod", source = "paymentMethod")
  @Mapping(target = "status", source = "status")
  @Mapping(target = "customerId", source = "customerId")
  @Mapping(target = "customerName", ignore = true)
  @Mapping(target = "customerAddress", ignore = true)
  @Mapping(target = "customerPhone", ignore = true)
  public abstract CheckoutResponse toCheckoutResponse(Purchase purchase);

  @AfterMapping
  protected void populateCustomerDetails(@MappingTarget CheckoutResponse response, Purchase purchase) {
    // If customerId exists, fetch customer details
    if (purchase.getCustomerId() != null && !purchase.getCustomerId().trim().isEmpty()) {
      customerService.getCustomerById(purchase.getCustomerId()).ifPresent(customer -> {
        response.setCustomerName(customer.getName());
        response.setCustomerAddress(customer.getAddress());
        response.setCustomerPhone(customer.getPhone());
      });
    } else if (purchase.getCustomerName() != null && !purchase.getCustomerName().trim().isEmpty()) {
      // If only customerName is stored (no customerId), use it directly
      response.setCustomerName(purchase.getCustomerName());
    }
  }

  // Method to map Purchase to PurchaseSummaryDto
  @Mapping(target = "purchaseId", source = "id")
  @Mapping(target = "invoiceId", source = "invoiceId")
  @Mapping(target = "invoiceNo", source = "invoiceNo")
  @Mapping(target = "businessType", source = "businessType")
  @Mapping(target = "userId", source = "userId")
  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "items", expression = "java(purchase.getItems() != null ? purchase.getItems() : java.util.List.of())")
  @Mapping(target = "subTotal", source = "subTotal")
  @Mapping(target = "taxTotal", source = "taxTotal")
  @Mapping(target = "sgstAmount", source = "sgstAmount")
  @Mapping(target = "cgstAmount", source = "cgstAmount")
  @Mapping(target = "discountTotal", source = "discountTotal")
  @Mapping(target = "grandTotal", source = "grandTotal")
  @Mapping(target = "soldAt", source = "soldAt")
  @Mapping(target = "status", source = "status")
  @Mapping(target = "paymentMethod", source = "paymentMethod")
  @Mapping(target = "customerId", source = "customerId")
  @Mapping(target = "customerName", ignore = true)
  @Mapping(target = "customerAddress", ignore = true)
  @Mapping(target = "customerPhone", ignore = true)
  public abstract PurchaseSummaryDto toPurchaseSummaryDto(Purchase purchase);

  @AfterMapping
  protected void populateCustomerDetails(@MappingTarget PurchaseSummaryDto dto, Purchase purchase) {
    // If customerId exists, fetch customer details
    if (purchase.getCustomerId() != null && !purchase.getCustomerId().trim().isEmpty()) {
      customerService.getCustomerById(purchase.getCustomerId()).ifPresent(customer -> {
        dto.setCustomerName(customer.getName());
        dto.setCustomerAddress(customer.getAddress());
        dto.setCustomerPhone(customer.getPhone());
      });
    } else if (purchase.getCustomerName() != null && !purchase.getCustomerName().trim().isEmpty()) {
      // If only customerName is stored (no customerId), use it directly
      dto.setCustomerName(purchase.getCustomerName());
    }
  }
}

