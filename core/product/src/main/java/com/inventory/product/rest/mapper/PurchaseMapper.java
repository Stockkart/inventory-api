package com.inventory.product.rest.mapper;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.rest.dto.sale.AddToCartRequest;
import com.inventory.product.rest.dto.sale.CheckoutRequest;
import com.inventory.product.rest.dto.sale.SaleStatusResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Mapper(componentModel = "spring", imports = {Instant.class, BigDecimal.class}, unmappedTargetPolicy = ReportingPolicy.IGNORE)
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
}

