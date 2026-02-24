package com.inventory.product.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.PurchaseStatus;
import com.inventory.product.domain.model.SchemeType;
import com.inventory.product.rest.dto.sale.AddToCartRequest;
import com.inventory.product.rest.dto.sale.CheckoutRequest;
import com.inventory.product.rest.dto.sale.UpdatePurchaseStatusRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

@Component
public class CheckoutValidator {

  private static final int MAX_QUANTITY = 1000;
  private static final int MAX_ITEMS_PER_SALE = 100;

  public void validateCheckoutItem(CheckoutRequest.CheckoutItem item) {
    if (!StringUtils.hasText(item.getId())) {
      throw new ValidationException("ID is required for item");
    }
    if (item.getQuantity() == null || item.getQuantity() <= 0) {
      throw new ValidationException("Invalid quantity for item: " + item.getId());
    }
    if (item.getQuantity() > MAX_QUANTITY) {
      throw new ValidationException("Maximum quantity per item is " + MAX_QUANTITY);
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
    if (request.getItems().size() > MAX_ITEMS_PER_SALE) {
      throw new ValidationException("Exceeded maximum number of items per sale (" + MAX_ITEMS_PER_SALE + ")");
    }
  }

  public void validateCartItem(AddToCartRequest.CartItem item) {
    if (!StringUtils.hasText(item.getId())) {
      throw new ValidationException("ID is required for item");
    }
    if (StringUtils.hasText(item.getUnit()) && !item.getUnit().trim().toUpperCase().matches("^[A-Z0-9_]+$")) {
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
      if (item.getQuantity() != null && Math.abs(item.getQuantity()) > MAX_QUANTITY) {
        throw new ValidationException("Maximum quantity per item is " + MAX_QUANTITY);
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

}
