package com.inventory.product.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.domain.model.enums.SchemeType;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.utils.CheckoutUtils;
import com.inventory.product.rest.dto.request.AddToCartRequest;
import com.inventory.product.rest.dto.request.CheckoutRequest;
import com.inventory.product.rest.dto.request.UpdatePurchaseStatusRequest;
import com.inventory.product.utils.constants.ProductConstants;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class CheckoutValidator {

  public void validateCheckoutItem(CheckoutRequest.CheckoutItem item) {
    if (!StringUtils.hasText(item.getId())) {
      throw new ValidationException("ID is required for item");
    }
    if (item.getQuantity() == null || item.getQuantity() <= 0) {
      throw new ValidationException("Invalid quantity for item: " + item.getId());
    }
    if (item.getQuantity() > ProductConstants.MAX_QUANTITY_PER_ITEM) {
      throw new ValidationException("Maximum quantity per item is " + ProductConstants.MAX_QUANTITY_PER_ITEM);
    }
    if (item.getPriceToRetail() == null || item.getPriceToRetail().compareTo(BigDecimal.ZERO) <= 0) {
      throw new ValidationException("Selling price must be greater than zero for item: " + item.getId());
    }
  }

  public void validateShopIdAndUserId(String shopId, String userId) {
    if (shopId == null || userId == null) {
      throw new ValidationException("Shop ID and User ID are required. Please ensure you are authenticated.");
    }
  }

  public void validateAddToCartRequest(AddToCartRequest request) {
    if (request == null) {
      throw new ValidationException("Add to cart request cannot be null");
    }
    if (!StringUtils.hasText(request.getBusinessType())) {
      throw new ValidationException("Business type is required");
    }
    if (request.getItems().size() > ProductConstants.MAX_ITEMS_PER_SALE) {
      throw new ValidationException("Exceeded maximum number of items per sale (" + ProductConstants.MAX_ITEMS_PER_SALE + ")");
    }
  }

  public void validateCartItem(AddToCartRequest.CartItem item) {
    if (!StringUtils.hasText(item.getId())) {
      throw new ValidationException("ID is required for item");
    }
    if (StringUtils.hasText(item.getUnit()) && !item.getUnit().trim().toUpperCase().matches("^[A-Z0-9_ ]+$")) {
      throw new ValidationException("Invalid unit for item: " + item.getId());
    }
    // Quantity may be null or 0 when only updating additionalDiscount, scheme, or priceToRetail (item must already be in cart)
    boolean hasSchemeChange = item.getSchemePayFor() != null || item.getSchemeFree() != null
        || item.getSchemeType() != null || item.getSchemePercentage() != null;
    boolean hasBaseQuantity = item.getBaseQuantity() != null && item.getBaseQuantity() != 0;
    boolean hasQuantity = item.getQuantity() != null && item.getQuantity() != 0;
    boolean updateOnly = (item.getQuantity() == null || item.getQuantity() == 0)
        && !hasBaseQuantity
        && (item.getAdditionalDiscount() != null || hasSchemeChange || item.getPriceToRetail() != null);
    if (!updateOnly) {
      if (!hasQuantity && !hasBaseQuantity) {
        throw new ValidationException("Quantity or baseQuantity is required for item: " + item.getId());
      }
      if (item.getQuantity() != null && Math.abs(item.getQuantity()) > ProductConstants.MAX_QUANTITY_PER_ITEM) {
        throw new ValidationException("Maximum quantity per item is " + ProductConstants.MAX_QUANTITY_PER_ITEM);
      }
      if (item.getBaseQuantity() != null && item.getBaseQuantity() == 0) {
        throw new ValidationException("baseQuantity cannot be zero for item: " + item.getId());
      }
      if (hasQuantity && hasBaseQuantity) {
        int quantitySign = Integer.signum(item.getQuantity());
        int baseQuantitySign = Integer.signum(item.getBaseQuantity());
        if (quantitySign != baseQuantitySign) {
          throw new ValidationException("quantity and baseQuantity must have same direction for item: " + item.getId());
        }
      }
    }
    // Selling price is optional. When omitted for positive quantity, backend uses inventory default price
    // for the selected sale unit. When provided, it must be > 0.
    if (item.getPriceToRetail() != null && item.getPriceToRetail().compareTo(BigDecimal.ZERO) <= 0) {
      throw new ValidationException("Selling price must be greater than zero for item: " + item.getId());
    }
    // additionalDiscount is optional; when provided it must be a valid percentage (0–100)
    if (item.getAdditionalDiscount() != null) {
      if (item.getAdditionalDiscount().compareTo(BigDecimal.ZERO) < 0
          || item.getAdditionalDiscount().compareTo(BigDecimal.valueOf(100)) > 0) {
        throw new ValidationException("Additional discount for item " + item.getId() + " must be between 0 and 100");
      }
    }
    // Scheme validation:
    // - When schemeType is PERCENTAGE, schemePercentage must be between 0 and 100 (inclusive).
    // - Otherwise, when schemePayFor / schemeFree are provided, they must be valid.
    if (item.getSchemeType() == SchemeType.PERCENTAGE) {
      if (item.getSchemePercentage() == null) {
        throw new ValidationException("Scheme percentage is required when schemeType is PERCENTAGE for item: " + item.getId());
      }
      if (item.getSchemePercentage().compareTo(BigDecimal.ZERO) < 0
          || item.getSchemePercentage().compareTo(BigDecimal.valueOf(100)) > 0) {
        throw new ValidationException("Scheme percentage for item " + item.getId() + " must be between 0 and 100 (inclusive)");
      }
    } else {
      // Scheme: when provided, schemePayFor must be > 0 and schemeFree must be >= 0 (e.g. "2 free on 10" = payFor 10, free 2)
      if (item.getSchemePayFor() != null && item.getSchemePayFor() <= 0) {
        throw new ValidationException("Scheme pay-for for item " + item.getId() + " must be greater than zero");
      }
      if (item.getSchemeFree() != null && item.getSchemeFree() < 0) {
        throw new ValidationException("Scheme free units for item " + item.getId() + " must be zero or greater");
      }
    }
  }

  public void validateUpdateStatusRequest(UpdatePurchaseStatusRequest request) {
    if (request == null) {
      throw new ValidationException("Update purchase status request cannot be null");
    }
    if (request.getPurchaseId() == null || !StringUtils.hasText(request.getPurchaseId())) {
      throw new ValidationException("Purchase ID is required");
    }
    if (request.getStatus() == null) {
      throw new ValidationException("Status is required");
    }
    if (request.getStatus() == PurchaseStatus.PENDING && StringUtils.hasText(request.getPaymentMethod())) {
      String m = request.getPaymentMethod().trim().toUpperCase();
      if ("SPLIT".equals(m) || "MULTI".equals(m)) {
        throw new ValidationException("Split and multi payment can only be chosen when completing the sale");
      }
    }
  }

  private static BigDecimal s2(BigDecimal v) {
    if (v == null) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    return v.setScale(2, RoundingMode.HALF_UP);
  }

  private static boolean sameTotal(BigDecimal total, BigDecimal cash, BigDecimal online, BigDecimal credit) {
    return s2(total).compareTo(s2(cash).add(s2(online)).add(s2(credit))) == 0;
  }

  /**
   * Resolves cash/online/credit amounts for COMPLETED checkout. Caller must only invoke when status is COMPLETED.
   */
  public PaymentBreakdown resolvePaymentBreakdown(UpdatePurchaseStatusRequest request, Purchase purchase) {
    BigDecimal total = s2(purchase.getGrandTotal());
    String method = StringUtils.hasText(request.getPaymentMethod())
        ? request.getPaymentMethod().trim().toUpperCase()
        : "CASH";
    if (method.isEmpty()) {
      method = "CASH";
    }

    BigDecimal cash = s2(request.getAmountPaidCash());
    BigDecimal online = s2(request.getAmountPaidOnline());
    BigDecimal credit = s2(request.getAmountOnCredit());
    BigDecimal zero = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    switch (method) {
      case "CASH":
        if (cash.signum() == 0 && online.signum() == 0 && credit.signum() == 0) {
          return new PaymentBreakdown(total, zero, zero, "CASH");
        }
        if (!sameTotal(total, cash, online, credit) || online.signum() != 0 || credit.signum() != 0) {
          throw new ValidationException("CASH: paid amount must equal grand total (cash only)");
        }
        return new PaymentBreakdown(cash, zero, zero, "CASH");
      case "ONLINE":
        if (cash.signum() == 0 && online.signum() == 0 && credit.signum() == 0) {
          return new PaymentBreakdown(zero, total, zero, "ONLINE");
        }
        if (!sameTotal(total, cash, online, credit) || cash.signum() != 0 || credit.signum() != 0) {
          throw new ValidationException("ONLINE: paid amount must equal grand total (online only)");
        }
        return new PaymentBreakdown(zero, online, zero, "ONLINE");
      case "CREDIT":
        if (!StringUtils.hasText(purchase.getCustomerId())) {
          throw new ValidationException("Customer is required for credit sales. Add customer from cart before checkout.");
        }
        if (cash.signum() == 0 && online.signum() == 0 && credit.signum() == 0) {
          return new PaymentBreakdown(zero, zero, total, "CREDIT");
        }
        if (!sameTotal(total, cash, online, credit) || cash.signum() != 0 || online.signum() != 0) {
          throw new ValidationException("CREDIT: full amount must be on credit");
        }
        return new PaymentBreakdown(zero, zero, credit, "CREDIT");
      case "SPLIT":
        if (!StringUtils.hasText(purchase.getCustomerId())) {
          throw new ValidationException("Customer is required for split payment (credit + cash or online)");
        }
        if (credit.compareTo(zero) <= 0) {
          throw new ValidationException("Split payment: amount on credit must be greater than zero");
        }
        boolean hasCash = cash.compareTo(zero) > 0;
        boolean hasOnline = online.compareTo(zero) > 0;
        if (hasCash == hasOnline) {
          throw new ValidationException("Split payment: pay the remainder with exactly one of cash or online (not both, not neither)");
        }
        if (!sameTotal(total, cash, online, credit)) {
          throw new ValidationException("Split payment: cash + online + credit must equal grand total");
        }
        return new PaymentBreakdown(cash, online, credit, "SPLIT");
      case "MULTI":
        if (cash.compareTo(zero) <= 0 || online.compareTo(zero) <= 0) {
          throw new ValidationException("Multi payment: both cash and online amounts must be greater than zero");
        }
        if (credit.signum() != 0) {
          throw new ValidationException("Multi payment cannot include credit; use Split for credit + paid");
        }
        if (!sameTotal(total, cash, online, credit)) {
          throw new ValidationException("Multi payment: cash + online must equal grand total");
        }
        return new PaymentBreakdown(cash, online, zero, "MULTI");
      default:
        throw new ValidationException("Invalid payment method. Use CASH, ONLINE, CREDIT, SPLIT, or MULTI");
    }
  }

  public static final class PaymentBreakdown {
    private final BigDecimal amountPaidCash;
    private final BigDecimal amountPaidOnline;
    private final BigDecimal amountOnCredit;
    private final String paymentMethod;

    public PaymentBreakdown(BigDecimal amountPaidCash, BigDecimal amountPaidOnline,
        BigDecimal amountOnCredit, String paymentMethod) {
      this.amountPaidCash = amountPaidCash;
      this.amountPaidOnline = amountPaidOnline;
      this.amountOnCredit = amountOnCredit;
      this.paymentMethod = paymentMethod;
    }

    public BigDecimal getAmountPaidCash() {
      return amountPaidCash;
    }

    public BigDecimal getAmountPaidOnline() {
      return amountPaidOnline;
    }

    public BigDecimal getAmountOnCredit() {
      return amountOnCredit;
    }

    public String getPaymentMethod() {
      return paymentMethod;
    }
  }

  public void validateStatusTransition(PurchaseStatus currentStatus, PurchaseStatus requestedStatus) {
    // If status is not changing, allow it
    if (currentStatus == requestedStatus) {
      return;
    }

    // COMPLETED -> any status: Not allowed
    if (currentStatus == PurchaseStatus.COMPLETED) {
      throw new ValidationException("Cannot change status from COMPLETED. Purchase is already completed.");
    }

    // CREATED -> PENDING:
    if (currentStatus == PurchaseStatus.CREATED && requestedStatus == PurchaseStatus.PENDING) {
      return;
    }

    // PENDING or CREATED / COMPLETED: Allowed
    if (currentStatus == PurchaseStatus.PENDING) {
      if (requestedStatus == PurchaseStatus.CREATED || requestedStatus == PurchaseStatus.COMPLETED) {
        return;
      }
    }

    // Any other transition: Not allowed
    throw new ValidationException(
        String.format("Invalid status transition from %s to %s. " +
                "Allowed transitions: CREATED->PENDING, PENDING->CREATED, PENDING->COMPLETED",
            currentStatus, requestedStatus));
  }

  /**
   * Resolve and validate that all cart items use a consistent billing mode. Cannot mix REGULAR and BASIC.
   */
  public BillingMode resolveAndValidateCartBillingMode(Purchase existingCart, List<PurchaseItem> newItems) {
    Set<BillingMode> observed = new HashSet<>();
    BillingMode existingCartMode = existingCart != null ? existingCart.getBillingMode() : null;
    List<PurchaseItem> existingItems = existingCart != null && existingCart.getItems() != null
        ? existingCart.getItems()
        : List.of();
    boolean hasExistingItems = !existingItems.isEmpty();
    if (hasExistingItems) {
      observed.add(CheckoutUtils.normalizeBillingMode(existingCartMode));
      for (PurchaseItem item : existingItems) {
        observed.add(CheckoutUtils.normalizeBillingMode(item.getBillingMode()));
      }
    }
    if (newItems != null) {
      for (PurchaseItem item : newItems) {
        observed.add(CheckoutUtils.normalizeBillingMode(item.getBillingMode()));
      }
    }
    if (observed.size() > 1) {
      throw new ValidationException("Cannot mix REGULAR and BASIC inventory items in a single cart");
    }
    if (observed.isEmpty()) {
      return BillingMode.REGULAR;
    }
    return observed.iterator().next();
  }
}
