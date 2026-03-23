package com.inventory.product.mapper;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.domain.model.enums.SchemeType;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.pricing.InventoryPricingReadHandler;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.request.AddToCartRequest;
import com.inventory.product.rest.dto.request.CheckoutRequest;
import com.inventory.product.rest.dto.response.AddToCartResponse;
import com.inventory.product.rest.dto.response.CheckoutResponse;
import com.inventory.product.rest.dto.response.PurchaseListResponse;
import com.inventory.product.rest.dto.response.PurchaseSummaryDto;
import com.inventory.product.rest.dto.response.SaleStatusResponse;
import com.inventory.user.service.CustomerService;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Mapper(componentModel = "spring", imports = {Instant.class, BigDecimal.class, PurchaseStatus.class}, unmappedTargetPolicy = ReportingPolicy.IGNORE)
public abstract class PurchaseMapper {
  
  @Autowired
  protected CustomerService customerService;
  
  @Autowired
  protected ShopRepository shopRepository;

  @Autowired
  protected InventoryRepository inventoryRepository;

  @Autowired
  protected InventoryPricingReadHandler inventoryPricingReadHandler;


  @Mapping(target = "saleId", source = "id")
  @Mapping(target = "valid", source = "valid")
  public abstract SaleStatusResponse toStatusResponse(Purchase purchase);

  // New mapping methods for creating Purchase and PurchaseItem

  // MongoDB will auto-generate the id as ObjectId
  @Mapping(target = "id", ignore = true)
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
  @Mapping(target = "quantity", expression = "java(item.getQuantity() != null ? java.math.BigDecimal.valueOf(item.getQuantity()) : java.math.BigDecimal.ZERO)")
  @Mapping(target = "maximumRetailPrice", source = "inventory.maximumRetailPrice")
  @Mapping(target = "priceToRetail", source = "item.priceToRetail")
  @Mapping(target = "discount", expression = "java(calculateDiscount(inventory.getMaximumRetailPrice(), item.getPriceToRetail()))")
  @Mapping(target = "additionalDiscount", source = "inventory.additionalDiscount")
  @Mapping(target = "totalAmount", expression = "java(calculateTotalAmount(item.getPriceToRetail(), inventory.getAdditionalDiscount(), item.getQuantity() != null ? java.math.BigDecimal.valueOf(item.getQuantity()) : java.math.BigDecimal.ZERO, inventory.getCgst(), inventory.getSgst(), inventory.getShopId()))")
  @Mapping(target = "sgst", source = "inventory.sgst")
  @Mapping(target = "cgst", source = "inventory.cgst")
  @Mapping(target = "costPrice", source = "inventory.costPrice")
  public abstract PurchaseItem toPurchaseItem(CheckoutRequest.CheckoutItem item, Inventory inventory);

  @AfterMapping
  protected void setShopTaxRatesIfNullForCheckout(@MappingTarget PurchaseItem purchaseItem, Inventory inventory) {
    // If CGST/SGST are null, use shop-level rates
    if ((purchaseItem.getCgst() == null || purchaseItem.getCgst().trim().isEmpty()) 
        || (purchaseItem.getSgst() == null || purchaseItem.getSgst().trim().isEmpty())) {
      if (inventory.getShopId() != null) {
        shopRepository.findById(inventory.getShopId()).ifPresent(shop -> {
          if (purchaseItem.getCgst() == null || purchaseItem.getCgst().trim().isEmpty()) {
            purchaseItem.setCgst(shop.getCgst());
          }
          if (purchaseItem.getSgst() == null || purchaseItem.getSgst().trim().isEmpty()) {
            purchaseItem.setSgst(shop.getSgst());
          }
          // Recalculate totalAmount with shop rates if it was calculated with null rates
          if (purchaseItem.getPriceToRetail() != null && purchaseItem.getQuantity() != null) {
            purchaseItem.setTotalAmount(calculateTotalAmount(
                purchaseItem.getPriceToRetail(),
                purchaseItem.getAdditionalDiscount(),
                purchaseItem.getQuantity(),
                purchaseItem.getCgst(),
                purchaseItem.getSgst(),
                inventory.getShopId()
            ));
          }
        });
      }
    }
    enrichPurchaseItemMargin(purchaseItem);
  }

  @Mapping(target = "inventoryId", source = "item.id")
  @Mapping(target = "name", source = "inventory.name")
  @Mapping(target = "quantity", expression = "java(item.getQuantity() != null ? java.math.BigDecimal.valueOf(item.getQuantity()) : java.math.BigDecimal.ZERO)")
  @Mapping(target = "maximumRetailPrice", source = "inventory.maximumRetailPrice")
  @Mapping(target = "priceToRetail", source = "item.priceToRetail")
  @Mapping(target = "discount", expression = "java(calculateDiscount(inventory.getMaximumRetailPrice(), item.getPriceToRetail()))")
  @Mapping(target = "additionalDiscount", expression = "java(getEffectiveAdditionalDiscount(item, inventory))")
  @Mapping(target = "totalAmount", expression = "java(calculateTotalAmount(getEffectiveSellingPriceForCartItem(item), getEffectiveAdditionalDiscount(item, inventory), getBillableQuantityAsDecimalForCartItem(item), inventory.getCgst(), inventory.getSgst(), inventory.getShopId()))")
  @Mapping(target = "sgst", source = "inventory.sgst")
  @Mapping(target = "cgst", source = "inventory.cgst")
  @Mapping(target = "schemeType", source = "item.schemeType")
  @Mapping(target = "schemePayFor", source = "item.schemePayFor")
  @Mapping(target = "schemeFree", source = "item.schemeFree")
  @Mapping(target = "schemePercentage", source = "item.schemePercentage")
  @Mapping(target = "costPrice", source = "inventory.costPrice")
  public abstract PurchaseItem toPurchaseItemFromCartItem(AddToCartRequest.CartItem item, Inventory inventory);

  @AfterMapping
  protected void setDefaultSchemeFromInventoryWhenFirstAdd(@MappingTarget PurchaseItem purchaseItem,
                                                           AddToCartRequest.CartItem item, Inventory inventory) {
    // When cart item doesn't provide scheme, set default from inventory (first-time add). Later merges keep existing.
    if (item.getSchemePayFor() != null || item.getSchemeFree() != null
        || item.getSchemeType() != null || item.getSchemePercentage() != null) {
      return; // Request provided scheme, use it (already mapped)
    }
    if (inventory.getSchemeType() == SchemeType.PERCENTAGE
        && inventory.getSchemePercentage() != null && inventory.getSchemePercentage().signum() > 0) {
      purchaseItem.setSchemeType(SchemeType.PERCENTAGE);
      purchaseItem.setSchemePercentage(inventory.getSchemePercentage());
    } else if (inventory.getSchemePayFor() != null && inventory.getSchemePayFor() > 0
        && inventory.getSchemeFree() != null && inventory.getSchemeFree() >= 0) {
      purchaseItem.setSchemeType(SchemeType.FIXED_UNITS);
      purchaseItem.setSchemePayFor(inventory.getSchemePayFor());
      purchaseItem.setSchemeFree(inventory.getSchemeFree());
      purchaseItem.setSchemePercentage(null);
    } else if (inventory.getScheme() != null && inventory.getScheme() > 0
        && getReceivedBaseCount(inventory) > 0) {
      // Legacy: scheme = free units, derive payFor/free from received
      int received = getReceivedBaseCount(inventory);
      int free = inventory.getScheme();
      int paid = received - free;
      if (paid <= 0) {
        return;
      }
      int g = gcd(paid, free);
      purchaseItem.setSchemePayFor(paid / g);
      purchaseItem.setSchemeFree(free / g);
      purchaseItem.setSchemeType(SchemeType.FIXED_UNITS);
      purchaseItem.setSchemePercentage(null);
    } else {
      return;
    }
    // Recalculate totalAmount (PERCENTAGE: scheme on price; FIXED_UNITS: ratio on quantity)
    if (purchaseItem.getPriceToRetail() != null && purchaseItem.getQuantity() != null && inventory.getShopId() != null) {
      BigDecimal effectivePrice = getEffectiveSellingPriceFromPurchaseItem(purchaseItem);
      BigDecimal billableQty = getBillableQuantityAsDecimalFromPurchaseItem(purchaseItem);
      purchaseItem.setTotalAmount(calculateTotalAmount(
          effectivePrice,
          purchaseItem.getAdditionalDiscount(),
          billableQty,
          purchaseItem.getCgst(),
          purchaseItem.getSgst(),
          inventory.getShopId()
      ));
    }
    enrichPurchaseItemMargin(purchaseItem);
  }

  @AfterMapping
  protected void enrichPurchaseItemMarginFromCart(@MappingTarget PurchaseItem purchaseItem, AddToCartRequest.CartItem item, Inventory inventory) {
    enrichPurchaseItemMargin(purchaseItem);
  }

  private static int gcd(int a, int b) {
    a = Math.abs(a);
    b = Math.abs(b);
    while (b != 0) {
      int t = b;
      b = a % b;
      a = t;
    }
    return a;
  }

  private int getReceivedBaseCount(Inventory inventory) {
    if (inventory.getReceivedBaseCount() != null) {
      return inventory.getReceivedBaseCount();
    }
    if (inventory.getReceivedCount() == null) {
      return 0;
    }
    int factor = 1;
    if (inventory.getUnitConversions() != null
        && inventory.getUnitConversions().getFactor() != null
        && inventory.getUnitConversions().getFactor() > 0) {
      factor = inventory.getUnitConversions().getFactor();
    }
    return inventory.getReceivedCount()
        .multiply(BigDecimal.valueOf(factor))
        .setScale(0, RoundingMode.HALF_UP)
        .intValue();
  }

  @AfterMapping
  protected void setShopTaxRatesIfNull(@MappingTarget PurchaseItem purchaseItem, Inventory inventory) {
    // If CGST/SGST are null, use shop-level rates
    if ((purchaseItem.getCgst() == null || purchaseItem.getCgst().trim().isEmpty()) 
        || (purchaseItem.getSgst() == null || purchaseItem.getSgst().trim().isEmpty())) {
      if (inventory.getShopId() != null) {
        shopRepository.findById(inventory.getShopId()).ifPresent(shop -> {
          if (purchaseItem.getCgst() == null || purchaseItem.getCgst().trim().isEmpty()) {
            purchaseItem.setCgst(shop.getCgst());
          }
          if (purchaseItem.getSgst() == null || purchaseItem.getSgst().trim().isEmpty()) {
            purchaseItem.setSgst(shop.getSgst());
          }
          // Recalculate totalAmount with shop rates (FIXED_UNITS: ratio on quantity)
          if (purchaseItem.getPriceToRetail() != null && purchaseItem.getQuantity() != null) {
            BigDecimal effectivePrice = getEffectiveSellingPriceFromPurchaseItem(purchaseItem);
            BigDecimal billableQty = getBillableQuantityAsDecimalFromPurchaseItem(purchaseItem);
            purchaseItem.setTotalAmount(calculateTotalAmount(
                effectivePrice,
                purchaseItem.getAdditionalDiscount(),
                billableQty,
                purchaseItem.getCgst(),
                purchaseItem.getSgst(),
                inventory.getShopId()
            ));
          }
        });
      }
    }
  }

  /** Billable quantity as decimal: FIXED_UNITS uses ratio schemePayFor/(schemePayFor+schemeFree) on any qty. */
  protected BigDecimal getBillableQuantityAsDecimalFromPurchaseItem(PurchaseItem item) {
    BigDecimal totalQty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
    if (totalQty.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
    if (item.getSchemeType() == SchemeType.PERCENTAGE) {
      return totalQty;
    }
    if (item.getSchemeType() == SchemeType.FIXED_UNITS && item.getSchemePayFor() != null && item.getSchemePayFor() > 0
        && item.getSchemeFree() != null && item.getSchemeFree() >= 0) {
      BigDecimal payFor = BigDecimal.valueOf(item.getSchemePayFor());
      BigDecimal free = BigDecimal.valueOf(item.getSchemeFree());
      BigDecimal sum = payFor.add(free);
      if (sum.compareTo(BigDecimal.ZERO) <= 0) return totalQty;
      return totalQty.multiply(payFor).divide(sum, 4, java.math.RoundingMode.HALF_UP);
    }
    return totalQty;
  }

  /** @deprecated Use getBillableQuantityAsDecimalFromPurchaseItem for amount calculations. */
  protected int getPaidQuantityFromPurchaseItem(PurchaseItem item) {
    return getBillableQuantityAsDecimalFromPurchaseItem(item).setScale(0, java.math.RoundingMode.HALF_UP).intValue();
  }

  /** Effective selling price per unit for PurchaseItem. PERCENTAGE: priceToRetail * (1 - schemePercentage/100). */
  protected BigDecimal getEffectiveSellingPriceFromPurchaseItem(PurchaseItem item) {
    BigDecimal price = item.getPriceToRetail() != null ? item.getPriceToRetail() : BigDecimal.ZERO;
    if (item.getSchemeType() == SchemeType.PERCENTAGE && item.getSchemePercentage() != null
        && item.getSchemePercentage().signum() > 0) {
      BigDecimal pct = item.getSchemePercentage();
      return price.multiply(BigDecimal.ONE.subtract(pct.divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP)));
    }
    return price;
  }

  /** Billable quantity for PurchaseItem (decimal). */
  protected BigDecimal getBillableQuantityFromPurchaseItemAsDecimal(PurchaseItem item) {
    return getBillableQuantityAsDecimalFromPurchaseItem(item);
  }

  /**
   * Compute and set margin breakdown on a purchase item: costTotal, profit, marginPercent.
   * Uses costPrice × billableQty for cost; revenue before tax = totalAmount / (1 + tax rates); profit = revenue − cost.
   */
  public void enrichPurchaseItemMargin(PurchaseItem item) {
    if (item == null || item.getCostPrice() == null) {
      return;
    }
    BigDecimal billableQty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
    BigDecimal costTotal = item.getCostPrice().multiply(billableQty).setScale(2, java.math.RoundingMode.HALF_UP);
    item.setCostTotal(costTotal);

    BigDecimal totalAmount = item.getTotalAmount();
    if (totalAmount == null || totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
      item.setProfit(BigDecimal.ZERO);
      item.setMarginPercent(null);
      return;
    }
    BigDecimal taxMultiplier = BigDecimal.ONE;
    if (item.getCgst() != null && !item.getCgst().trim().isEmpty()) {
      try {
        BigDecimal cgstRate = new BigDecimal(item.getCgst().trim()).divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP);
        taxMultiplier = taxMultiplier.add(cgstRate);
      } catch (NumberFormatException ignored) { }
    }
    if (item.getSgst() != null && !item.getSgst().trim().isEmpty()) {
      try {
        BigDecimal sgstRate = new BigDecimal(item.getSgst().trim()).divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP);
        taxMultiplier = taxMultiplier.add(sgstRate);
      } catch (NumberFormatException ignored) { }
    }
    if (taxMultiplier.compareTo(BigDecimal.ZERO) <= 0) {
      taxMultiplier = BigDecimal.ONE;
    }
    BigDecimal revenueBeforeTax = totalAmount.divide(taxMultiplier, 2, java.math.RoundingMode.HALF_UP);
    BigDecimal profit = revenueBeforeTax.subtract(costTotal).setScale(2, java.math.RoundingMode.HALF_UP);
    item.setProfit(profit);
    if (revenueBeforeTax.compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal marginPercent = profit.multiply(BigDecimal.valueOf(100)).divide(revenueBeforeTax, 2, java.math.RoundingMode.HALF_UP);
      item.setMarginPercent(marginPercent);
    } else {
      item.setMarginPercent(null);
    }
  }

  /** Cart item's additionalDiscount overrides inventory's when provided. */
  protected BigDecimal getEffectiveAdditionalDiscount(AddToCartRequest.CartItem item, Inventory inventory) {
    return item.getAdditionalDiscount() != null ? item.getAdditionalDiscount() : inventory.getAdditionalDiscount();
  }

  /** Billable quantity as decimal for cart item. FIXED_UNITS: qty * schemePayFor/(schemePayFor+schemeFree). */
  protected BigDecimal getBillableQuantityAsDecimalForCartItem(AddToCartRequest.CartItem item) {
    int totalQty = item.getQuantity() != null ? item.getQuantity() : 0;
    if (totalQty <= 0) return BigDecimal.ZERO;
    if (item.getSchemeType() == SchemeType.PERCENTAGE) {
      return BigDecimal.valueOf(totalQty);
    }
    if (item.getSchemePayFor() != null && item.getSchemePayFor() > 0
        && item.getSchemeFree() != null && item.getSchemeFree() >= 0) {
      BigDecimal payFor = BigDecimal.valueOf(item.getSchemePayFor());
      BigDecimal free = BigDecimal.valueOf(item.getSchemeFree());
      BigDecimal sum = payFor.add(free);
      if (sum.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.valueOf(totalQty);
      return BigDecimal.valueOf(totalQty).multiply(payFor).divide(sum, 4, java.math.RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(totalQty);
  }

  /** @deprecated Use getBillableQuantityAsDecimalForCartItem for amount calculations. */
  protected int getPaidQuantityForCartItem(AddToCartRequest.CartItem item) {
    return getBillableQuantityAsDecimalForCartItem(item).setScale(0, java.math.RoundingMode.HALF_UP).intValue();
  }

  /** Effective selling price for cart item. PERCENTAGE: priceToRetail * (1 - schemePercentage/100). */
  protected BigDecimal getEffectiveSellingPriceForCartItem(AddToCartRequest.CartItem item) {
    BigDecimal price = item.getPriceToRetail() != null ? item.getPriceToRetail() : BigDecimal.ZERO;
    if (item.getSchemeType() == SchemeType.PERCENTAGE && item.getSchemePercentage() != null
        && item.getSchemePercentage().signum() > 0) {
      BigDecimal pct = item.getSchemePercentage();
      return price.multiply(BigDecimal.ONE.subtract(pct.divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP)));
    }
    return price;
  }

  /** Billable quantity for cart item (decimal). */
  protected BigDecimal getBillableQuantityForCartItemAsDecimal(AddToCartRequest.CartItem item) {
    return getBillableQuantityAsDecimalForCartItem(item);
  }

  // Helper method to calculate discount: maximumRetailPrice - priceToRetail
  protected BigDecimal calculateDiscount(BigDecimal maximumRetailPrice, BigDecimal priceToRetail) {
    if (maximumRetailPrice == null || priceToRetail == null) {
      return BigDecimal.ZERO;
    }
    BigDecimal discount = maximumRetailPrice.subtract(priceToRetail);
    return discount.compareTo(BigDecimal.ZERO) > 0 ? discount : BigDecimal.ZERO;
  }

  /**
   * Calculate totalAmount for a purchase item.
   * @param billableQuantity can be fractional (e.g. for FIXED_UNITS scheme applied as ratio).
   */
  protected BigDecimal calculateTotalAmount(BigDecimal priceToRetail, BigDecimal additionalDiscount,
                                            BigDecimal billableQuantity, String cgst, String sgst, String shopId) {
    if (priceToRetail == null || billableQuantity == null || billableQuantity.compareTo(BigDecimal.ZERO) <= 0) {
      return BigDecimal.ZERO;
    }
    // Step 1: Calculate discounted/marked-up selling price per unit
    // Formula: price * (1 - additionalDiscount/100). Negative discount = markup (e.g. -2% => 1.02)
    BigDecimal discountedPricePerUnit = priceToRetail;
    if (additionalDiscount != null && additionalDiscount.compareTo(BigDecimal.ZERO) != 0) {
      BigDecimal discountMultiplier = BigDecimal.ONE.subtract(
          additionalDiscount.divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP)
      );
      discountedPricePerUnit = priceToRetail.multiply(discountMultiplier);
    }
    // Step 2: Multiply by billable quantity (can be fractional)
    BigDecimal totalDiscountedAmount = discountedPricePerUnit.multiply(billableQuantity);
    
    // Step 3: Add CGST and SGST - use shop-level rates if inventory rates are null
    String finalCgst = cgst;
    String finalSgst = sgst;
    
    // If inventory rates are null, fetch from shop
    if (shopId != null && ((finalCgst == null || finalCgst.trim().isEmpty()) || 
        (finalSgst == null || finalSgst.trim().isEmpty()))) {
      java.util.Optional<Shop> shopOpt = shopRepository.findById(shopId);
      if (shopOpt.isPresent()) {
        Shop shop = shopOpt.get();
        if (finalCgst == null || finalCgst.trim().isEmpty()) {
          finalCgst = shop.getCgst();
        }
        if (finalSgst == null || finalSgst.trim().isEmpty()) {
          finalSgst = shop.getSgst();
        }
      }
    }
    
    BigDecimal taxMultiplier = BigDecimal.ONE;
    if (finalCgst != null && !finalCgst.trim().isEmpty()) {
      try {
        BigDecimal cgstRate = new BigDecimal(finalCgst.trim()).divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP);
        taxMultiplier = taxMultiplier.add(cgstRate);
      } catch (NumberFormatException e) {
        // Invalid CGST rate, ignore
      }
    }
    if (finalSgst != null && !finalSgst.trim().isEmpty()) {
      try {
        BigDecimal sgstRate = new BigDecimal(finalSgst.trim()).divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP);
        taxMultiplier = taxMultiplier.add(sgstRate);
      } catch (NumberFormatException e) {
        // Invalid SGST rate, ignore
      }
    }
    
    BigDecimal totalAmount = totalDiscountedAmount.multiply(taxMultiplier);
    return totalAmount.setScale(2, java.math.RoundingMode.HALF_UP);
  }

  /** Overload for integer quantity (e.g. CheckoutRequest, createPurchaseItemWithTotal). */
  protected BigDecimal calculateTotalAmount(BigDecimal priceToRetail, BigDecimal additionalDiscount,
                                            Integer quantity, String cgst, String sgst, String shopId) {
    if (quantity == null) return BigDecimal.ZERO;
    return calculateTotalAmount(priceToRetail, additionalDiscount, BigDecimal.valueOf(quantity), cgst, sgst, shopId);
  }

  // Methods to create PurchaseItem
  public PurchaseItem createPurchaseItem(String inventoryId, String name, BigDecimal quantity,
                                          BigDecimal maximumRetailPrice, BigDecimal priceToRetail, BigDecimal discount) {
    PurchaseItem item = new PurchaseItem();
    item.setInventoryId(inventoryId);
    item.setName(name);
    item.setQuantity(quantity);
    item.setMaximumRetailPrice(maximumRetailPrice);
    item.setPriceToRetail(priceToRetail);
    item.setDiscount(discount);
    item.setAdditionalDiscount(null); // Not set for negative quantities
    item.setTotalAmount(BigDecimal.ZERO); // Not calculated for negative quantities
    // Note: CGST/SGST not set here as this method is used for negative quantities
    // For normal items, use toPurchaseItemFromCartItem which includes CGST/SGST from inventory
    return item;
  }
  
  /**
   * Create PurchaseItem with all fields including totalAmount calculation.
   */
  public PurchaseItem createPurchaseItemWithTotal(String inventoryId, String name, BigDecimal quantity,
                                                   BigDecimal maximumRetailPrice, BigDecimal priceToRetail, 
                                                   BigDecimal discount, BigDecimal additionalDiscount,
                                                   String cgst, String sgst, String shopId) {
    PurchaseItem item = new PurchaseItem();
    item.setInventoryId(inventoryId);
    item.setName(name);
    item.setQuantity(quantity);
    item.setMaximumRetailPrice(maximumRetailPrice);
    item.setPriceToRetail(priceToRetail);
    item.setDiscount(discount);
    item.setAdditionalDiscount(additionalDiscount);
    item.setCgst(cgst);
    item.setSgst(sgst);
    item.setTotalAmount(calculateTotalAmount(priceToRetail, additionalDiscount, quantity, cgst, sgst, shopId));
    return item;
  }

  // Method to create Purchase for cart
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "invoiceNo", ignore = true)
  @Mapping(target = "businessType", source = "request.businessType")
  @Mapping(target = "billingMode", source = "billingMode")
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
                             String shopId, String userId, String customerId, BillingMode billingMode);

  // Method to map Purchase to AddToCartResponse
  @Mapping(target = "purchaseId", source = "id")
  @Mapping(target = "invoiceNo", source = "invoiceNo")
  @Mapping(target = "businessType", source = "businessType")
  @Mapping(target = "billingMode", source = "billingMode")
  @Mapping(target = "userId", source = "userId")
  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "items", expression = "java(purchase.getItems() != null ? purchase.getItems() : java.util.List.of())")
  @Mapping(target = "subTotal", source = "subTotal")
  @Mapping(target = "taxTotal", source = "taxTotal")
  @Mapping(target = "sgstAmount", source = "sgstAmount")
  @Mapping(target = "cgstAmount", source = "cgstAmount")
  @Mapping(target = "discountTotal", source = "discountTotal")
  @Mapping(target = "additionalDiscountTotal", source = "additionalDiscountTotal")
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

  @AfterMapping
  protected void populatePurchaseValuesOnCartItems(@MappingTarget AddToCartResponse response, Purchase purchase) {
    if (response.getItems() == null) return;
    for (PurchaseItem item : response.getItems()) {
      if (item.getInventoryId() == null) continue;
      inventoryRepository.findById(item.getInventoryId()).ifPresent(inv -> {
        inventoryPricingReadHandler.enrich(inv);
        item.setPurchaseAdditionalDiscount(inv.getPurchaseAdditionalDiscount());
        item.setPurchaseSchemeType(inv.getPurchaseSchemeType());
        item.setPurchaseSchemePayFor(inv.getPurchaseSchemePayFor());
        item.setPurchaseSchemeFree(inv.getPurchaseSchemeFree());
        item.setPurchaseSchemePercentage(inv.getPurchaseSchemePercentage());
      });
    }
  }

  // Method to map Purchase to CheckoutResponse
  @Mapping(target = "invoiceNo", source = "invoiceNo")
  @Mapping(target = "businessType", source = "businessType")
  @Mapping(target = "billingMode", source = "billingMode")
  @Mapping(target = "userId", source = "userId")
  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "items", expression = "java(purchase.getItems() != null ? purchase.getItems() : java.util.List.of())")
  @Mapping(target = "subTotal", source = "subTotal")
  @Mapping(target = "taxTotal", source = "taxTotal")
  @Mapping(target = "sgstAmount", source = "sgstAmount")
  @Mapping(target = "cgstAmount", source = "cgstAmount")
  @Mapping(target = "discountTotal", source = "discountTotal")
  @Mapping(target = "additionalDiscountTotal", source = "additionalDiscountTotal")
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
  @Mapping(target = "invoiceNo", source = "invoiceNo")
  @Mapping(target = "businessType", source = "businessType")
  @Mapping(target = "billingMode", source = "billingMode")
  @Mapping(target = "userId", source = "userId")
  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "items", expression = "java(purchase.getItems() != null ? purchase.getItems() : java.util.List.of())")
  @Mapping(target = "subTotal", source = "subTotal")
  @Mapping(target = "taxTotal", source = "taxTotal")
  @Mapping(target = "sgstAmount", source = "sgstAmount")
  @Mapping(target = "cgstAmount", source = "cgstAmount")
  @Mapping(target = "discountTotal", source = "discountTotal")
  @Mapping(target = "additionalDiscountTotal", source = "additionalDiscountTotal")
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

  /**
   * Build paginated purchase list response. Use empty list for empty result.
   */
  public PurchaseListResponse toPurchaseListResponse(List<PurchaseSummaryDto> purchases,
      int page, int limit, long total, int totalPages) {
    PurchaseListResponse response = new PurchaseListResponse();
    response.setPurchases(purchases != null ? purchases : Collections.emptyList());
    response.setPage(page);
    response.setLimit(limit);
    response.setTotal(total);
    response.setTotalPages(totalPages);
    return response;
  }
}

