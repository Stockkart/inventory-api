package com.inventory.product.rest.mapper;

import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.model.Sale;
import com.inventory.product.domain.model.SaleItem;
import com.inventory.product.rest.dto.sale.CheckoutRequest;
import com.inventory.product.rest.dto.sale.CheckoutResponse;
import com.inventory.product.rest.dto.sale.SaleStatusResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Mapper(componentModel = "spring", imports = {UUID.class, Instant.class})
public interface SaleMapper {

  @Mapping(target = "saleId", source = "id")
  @Mapping(target = "items", expression = "java(toItemResponses(sale.getItems()))")
  CheckoutResponse toCheckoutResponse(Sale sale);

  @Mapping(target = "barcode", source = "productId")
  @Mapping(target = "price", source = "salePrice")
  CheckoutResponse.SaleItemResponse toItemResponse(SaleItem item);

  default List<CheckoutResponse.SaleItemResponse> toItemResponses(List<SaleItem> items) {
    return items == null ? java.util.List.of() :
            items.stream().map(this::toItemResponse).toList();
  }

  @Mapping(target = "saleId", source = "id")
  @Mapping(target = "valid", source = "valid")
  SaleStatusResponse toStatusResponse(Sale sale);

  // New mapping methods for creating Sale and SaleItem

  @Mapping(target = "id", expression = "java(\"sale-\" + UUID.randomUUID())")
  @Mapping(target = "invoiceId", expression = "java(UUID.randomUUID().toString())")
  @Mapping(target = "soldAt", expression = "java(Instant.now())")
  @Mapping(target = "valid", constant = "true")
  @Mapping(target = "items", source = "saleItems")
  @Mapping(target = "invoiceNo", ignore = true)
    // Will be set in service
  Sale toSale(CheckoutRequest request, List<SaleItem> saleItems,
              BigDecimal subTotal, BigDecimal taxTotal,
              BigDecimal discountTotal, BigDecimal grandTotal);

  @Mapping(target = "productId", source = "product.barcode")
  @Mapping(target = "productName", source = "product.name")
  @Mapping(target = "salePrice", source = "product.price")
  @Mapping(target = "quantity", source = "item.qty")
  @Mapping(target = "total", expression = "java(calculateItemTotal(product.getPrice(), item))")
  @Mapping(target = "discount", expression = "java(calculateDiscount(item.getDiscount(), product.getPrice(), item.getQty()))")
  SaleItem toSaleItem(CheckoutRequest.CheckoutItem item, Product product);

  // Helper methods for mapping expressions
  default BigDecimal calculateItemTotal(BigDecimal price, CheckoutRequest.CheckoutItem item) {
    if (item == null || item.getQty() == null) {
      return BigDecimal.ZERO;
    }

    int quantity = item.getQty();
    Integer discount = item.getDiscount();

    BigDecimal total = price.multiply(BigDecimal.valueOf(quantity));
    if (discount != null && discount > 0) {
      BigDecimal discountAmount = new BigDecimal(discount);
      if (discountAmount.compareTo(total) > 0) {
        discountAmount = total;
      }
      total = total.subtract(discountAmount);
    }
    return total.setScale(2, java.math.RoundingMode.HALF_UP);
  }

  default BigDecimal calculateDiscount(Integer discount, BigDecimal price, Integer quantity) {
    if (discount == null || discount <= 0 || quantity == null) {
      return BigDecimal.ZERO;
    }

    BigDecimal maxDiscount = price.multiply(BigDecimal.valueOf(quantity));
    BigDecimal discountAmount = new BigDecimal(discount);

    return discountAmount.compareTo(maxDiscount) > 0 ?
            maxDiscount.setScale(2, java.math.RoundingMode.HALF_UP) :
            discountAmount.setScale(2, java.math.RoundingMode.HALF_UP);
  }

  // Helper method to generate invoice number (moved from service)
  default String generateInvoiceNo() {
    String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    String random = String.format("%04d", (int) (Math.random() * 10_000));
    return "INV-" + timestamp + "-" + random;
  }
}

