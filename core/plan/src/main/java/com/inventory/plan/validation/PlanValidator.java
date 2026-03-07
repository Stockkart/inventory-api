package com.inventory.plan.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.Usage;
import com.inventory.plan.rest.dto.plan.AssignPlanRequest;
import com.inventory.plan.rest.dto.plan.PaymentWebhookPayload;
import com.inventory.plan.rest.dto.plan.RecordUsageRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

@Component
public class PlanValidator {

  public void validateAssignPlanRequest(String shopId, AssignPlanRequest request) {
    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("Shop ID is required");
    }
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    if (!StringUtils.hasText(request.getPlanId())) {
      throw new ValidationException("Plan ID is required");
    }
    if (request.getDurationMonths() == null || request.getDurationMonths() < 1) {
      throw new ValidationException("Duration must be at least 1 month");
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

  public void validatePaymentWebhookPayload(PaymentWebhookPayload payload) {
    if (payload == null) {
      throw new ValidationException("Webhook payload cannot be null");
    }
    if (!StringUtils.hasText(payload.getShopId())) {
      throw new ValidationException("Shop ID is required in webhook");
    }
    if (!StringUtils.hasText(payload.getPlanId())) {
      throw new ValidationException("Plan ID is required in webhook");
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
