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
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Mapper(componentModel = "spring", imports = {Instant.class, BigDecimal.class, PurchaseStatus.class}, unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface PurchaseMapper {


  @Mapping(target = "saleId", source = "id")
  @Mapping(target = "valid", source = "valid")
  SaleStatusResponse toStatusResponse(Purchase purchase);

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
  Purchase toPurchase(CheckoutRequest request, List<PurchaseItem> purchaseItems,
                      BigDecimal subTotal, BigDecimal taxTotal,
                      BigDecimal discountTotal, BigDecimal grandTotal);

  @Mapping(target = "inventoryId", source = "item.lotId")
  @Mapping(target = "name", source = "inventory.name")
  @Mapping(target = "quantity", source = "item.quantity")
  @Mapping(target = "maximumRetailPrice", source = "inventory.maximumRetailPrice")
  @Mapping(target = "sellingPrice", source = "item.sellingPrice")
  @Mapping(target = "discount", expression = "java(calculateDiscount(inventory.getMaximumRetailPrice(), item.getSellingPrice()))")
  PurchaseItem toPurchaseItem(CheckoutRequest.CheckoutItem item, Inventory inventory);

  @Mapping(target = "inventoryId", source = "item.lotId")
  @Mapping(target = "name", source = "inventory.name")
  @Mapping(target = "quantity", source = "item.quantity")
  @Mapping(target = "maximumRetailPrice", source = "inventory.maximumRetailPrice")
  @Mapping(target = "sellingPrice", source = "item.sellingPrice")
  @Mapping(target = "discount", expression = "java(calculateDiscount(inventory.getMaximumRetailPrice(), item.getSellingPrice()))")
  PurchaseItem toPurchaseItemFromCartItem(AddToCartRequest.CartItem item, Inventory inventory);

  // Helper method to calculate discount: maximumRetailPrice - sellingPrice
  default BigDecimal calculateDiscount(BigDecimal maximumRetailPrice, BigDecimal sellingPrice) {
    if (maximumRetailPrice == null || sellingPrice == null) {
      return BigDecimal.ZERO;
    }
    BigDecimal discount = maximumRetailPrice.subtract(sellingPrice);
    return discount.compareTo(BigDecimal.ZERO) > 0 ? discount : BigDecimal.ZERO;
  }

  // Helper method to generate invoice number (moved from service)
  default String generateInvoiceNo() {
    String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    String random = String.format("%04d", (int) (Math.random() * 10_000));
    return "INV-" + timestamp + "-" + random;
  }

  // Methods to create PurchaseItem
  default PurchaseItem createPurchaseItem(String inventoryId, String name, Integer quantity, 
                                          BigDecimal maximumRetailPrice, BigDecimal sellingPrice, BigDecimal discount) {
    PurchaseItem item = new PurchaseItem();
    item.setInventoryId(inventoryId);
    item.setName(name);
    item.setQuantity(quantity);
    item.setMaximumRetailPrice(maximumRetailPrice);
    item.setSellingPrice(sellingPrice);
    item.setDiscount(discount);
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
  @Mapping(target = "customerName", source = "request.customerName")
  @Mapping(target = "customerAddress", source = "request.customerAddress")
  @Mapping(target = "customerPhone", source = "request.customerPhone")
  Purchase toPurchaseForCart(AddToCartRequest request, List<PurchaseItem> purchaseItems,
                            BigDecimal subTotal, BigDecimal taxTotal,
                            BigDecimal discountTotal, BigDecimal grandTotal,
                            String shopId, String userId);

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
  @Mapping(target = "discountTotal", source = "discountTotal")
  @Mapping(target = "grandTotal", source = "grandTotal")
  @Mapping(target = "status", source = "status")
  @Mapping(target = "customerName", source = "customerName")
  @Mapping(target = "customerAddress", source = "customerAddress")
  @Mapping(target = "customerPhone", source = "customerPhone")
  @Mapping(target = "paymentMethod", source = "paymentMethod")
  AddToCartResponse toAddToCartResponse(Purchase purchase);

  // Method to map Purchase to CheckoutResponse
  @Mapping(target = "invoiceId", source = "invoiceId")
  @Mapping(target = "invoiceNo", source = "invoiceNo")
  @Mapping(target = "businessType", source = "businessType")
  @Mapping(target = "userId", source = "userId")
  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "items", expression = "java(purchase.getItems() != null ? purchase.getItems() : java.util.List.of())")
  @Mapping(target = "subTotal", source = "subTotal")
  @Mapping(target = "taxTotal", source = "taxTotal")
  @Mapping(target = "discountTotal", source = "discountTotal")
  @Mapping(target = "grandTotal", source = "grandTotal")
  @Mapping(target = "paymentMethod", source = "paymentMethod")
  @Mapping(target = "status", source = "status")
  @Mapping(target = "customerName", source = "customerName")
  @Mapping(target = "customerAddress", source = "customerAddress")
  @Mapping(target = "customerPhone", source = "customerPhone")
  CheckoutResponse toCheckoutResponse(Purchase purchase);

  // Method to map Purchase to PurchaseSummaryDto
  @Mapping(target = "purchaseId", source = "id")
  @Mapping(target = "items", expression = "java(purchase.getItems() != null ? purchase.getItems() : java.util.List.of())")
  PurchaseSummaryDto toPurchaseSummaryDto(Purchase purchase);
}

