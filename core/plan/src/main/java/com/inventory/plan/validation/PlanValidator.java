package com.inventory.plan.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.Usage;
import com.inventory.plan.rest.dto.request.CreatePlanCheckoutRequest;
import com.inventory.plan.rest.dto.request.RecordUsageRequest;
import com.inventory.plan.rest.dto.request.VerifyPlanPaymentRequest;
import com.inventory.plan.utils.CartUtils;
import com.inventory.plan.utils.constants.PlanPaymentConstants;
import com.inventory.plan.utils.constants.PricingConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

@Component
public class PlanValidator {

  public void validateCancelOrder(String shopId, String orderId) {
    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("Shop ID is required");
    }
    if (!StringUtils.hasText(orderId)) {
      throw new ValidationException("Order ID is required");
    }
  }

  public void validateRecordUsageRequest(RecordUsageRequest request) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    if (request.getBillingAmount() == null && request.getBillCount() == null
        && request.getSmsCount() == null && request.getWhatsappCount() == null) {
      throw new ValidationException("At least one usage field must be provided");
    }
    if (request.getBillCount() != null && request.getBillCount() < 0) {
      throw new ValidationException("Bill count cannot be negative");
    }
    if (request.getSmsCount() != null && request.getSmsCount() < 0) {
      throw new ValidationException("SMS count cannot be negative");
    }
    if (request.getWhatsappCount() != null && request.getWhatsappCount() < 0) {
      throw new ValidationException("WhatsApp count cannot be negative");
    }
    if (request.getBillingAmount() != null && request.getBillingAmount().signum() < 0) {
      throw new ValidationException("Billing amount cannot be negative");
    }
  }

  public void validateCreateCheckoutRequest(String shopId, CreatePlanCheckoutRequest request, String idempotencyKey) {
    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("Shop ID is required");
    }
    // No plan and at least one add-on or voucher is an add-on-only top-up, priced against the shop's current plan.
    if (!CartUtils.hasPlan(request) && !CartUtils.isAddOnOnly(request)) {
      throw new ValidationException(PricingConstants.MSG_EMPTY_CART);
    }
    if (idempotencyKey != null
        && (idempotencyKey.isBlank() || idempotencyKey.length() > PlanPaymentConstants.MAX_IDEMPOTENCY_KEY_LENGTH)) {
      throw new ValidationException(PlanPaymentConstants.IDEMPOTENCY_KEY_HEADER + " must be 1 to "
          + PlanPaymentConstants.MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
    }
    // Checkout charges one annual price, so any other duration would grant time that wasn't paid for.
    // Add-on-only orders grant no plan time, so their duration is ignored.
    if (CartUtils.hasPlan(request)
        && request.getDurationMonths() != null
        && request.getDurationMonths() != PlanPaymentConstants.DEFAULT_CHECKOUT_DURATION_MONTHS) {
      throw new ValidationException("Plans are sold yearly; duration must be "
          + PlanPaymentConstants.DEFAULT_CHECKOUT_DURATION_MONTHS + " months");
    }
  }

  public void validateVerifyPaymentRequest(VerifyPlanPaymentRequest request) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    if (!StringUtils.hasText(request.getOrderId())) {
      throw new ValidationException("Order ID is required");
    }
    if (!StringUtils.hasText(request.getRazorpayPaymentId())) {
      throw new ValidationException("Payment ID is required");
    }
    if (!StringUtils.hasText(request.getRazorpayOrderId())) {
      throw new ValidationException("Razorpay order ID is required");
    }
    if (!StringUtils.hasText(request.getRazorpaySignature())) {
      throw new ValidationException("Payment signature is required");
    }
  }

  /**
   * Validates that recording usage would not exceed plan limits. Throws if limits exceeded.
   */
  public void validateUsageWithinLimits(Plan plan, Usage usage, RecordUsageRequest request) {
    if (plan.isUnlimited()) {
      return;
    }
    if (request.getBillingAmount() != null && request.getBillingAmount().signum() > 0) {
      BigDecimal newTotal = (usage.getBillingAmountUsed() != null ? usage.getBillingAmountUsed() : BigDecimal.ZERO)
          .add(request.getBillingAmount());
      if (plan.getBillingLimit() != null && newTotal.compareTo(plan.getBillingLimit()) > 0) {
        throw new ValidationException("Billing limit exceeded. Cannot add this amount.");
      }
    }
    if (request.getBillCount() != null && request.getBillCount() > 0) {
      int newCount = (usage.getBillCountUsed() != null ? usage.getBillCountUsed() : 0) + request.getBillCount();
      if (plan.getBillCountLimit() != null && newCount > plan.getBillCountLimit()) {
        throw new ValidationException("Bill count limit exceeded.");
      }
    }
    if (request.getSmsCount() != null && request.getSmsCount() > 0 && plan.getSmsLimit() != null) {
      int newCount = (usage.getSmsUsed() != null ? usage.getSmsUsed() : 0) + request.getSmsCount();
      if (newCount > plan.getSmsLimit()) {
        throw new ValidationException("SMS limit exceeded.");
      }
    }
    if (request.getWhatsappCount() != null && request.getWhatsappCount() > 0 && plan.getWhatsappLimit() != null) {
      int newCount = (usage.getWhatsappUsed() != null ? usage.getWhatsappUsed() : 0) + request.getWhatsappCount();
      if (newCount > plan.getWhatsappLimit()) {
        throw new ValidationException("WhatsApp limit exceeded.");
      }
    }
  }

  /**
   * Returns which limits are reached for a shop's plan status.
   */
  public LimitReachedResult computeLimitsReached(Plan effectivePlan, Usage usage) {
    boolean billingLimitReached = false;
    boolean billCountLimitReached = false;
    boolean smsLimitReached = false;
    boolean whatsappLimitReached = false;

    if (!effectivePlan.isUnlimited()) {
      if (effectivePlan.getBillingLimit() != null && usage.getBillingAmountUsed() != null) {
        billingLimitReached = usage.getBillingAmountUsed().compareTo(effectivePlan.getBillingLimit()) >= 0;
      }
      if (effectivePlan.getBillCountLimit() != null && usage.getBillCountUsed() != null) {
        billCountLimitReached = usage.getBillCountUsed() >= effectivePlan.getBillCountLimit();
      }
      if (effectivePlan.getSmsLimit() != null && effectivePlan.getSmsLimit() > 0 && usage.getSmsUsed() != null) {
        smsLimitReached = usage.getSmsUsed() >= effectivePlan.getSmsLimit();
      }
      if (effectivePlan.getWhatsappLimit() != null && effectivePlan.getWhatsappLimit() > 0 && usage.getWhatsappUsed() != null) {
        whatsappLimitReached = usage.getWhatsappUsed() >= effectivePlan.getWhatsappLimit();
      }
    }
    return new LimitReachedResult(billingLimitReached, billCountLimitReached, smsLimitReached, whatsappLimitReached);
  }

  public record LimitReachedResult(
      boolean billingLimitReached,
      boolean billCountLimitReached,
      boolean smsLimitReached,
      boolean whatsappLimitReached) {}
}
