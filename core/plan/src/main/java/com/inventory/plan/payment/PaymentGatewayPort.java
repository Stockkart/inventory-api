package com.inventory.plan.payment;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import java.math.BigDecimal;
import java.util.Map;
import com.inventory.plan.payment.dto.CreateCheckoutCommand;
import com.inventory.plan.payment.dto.CreateCheckoutResult;
import com.inventory.plan.payment.dto.VerifyPaymentCommand;
import com.inventory.plan.payment.dto.VerifyPaymentResult;
import com.inventory.plan.payment.dto.WebhookHandleCommand;
import com.inventory.plan.payment.dto.WebhookHandleResult;

/**
 * Provider-agnostic payment gateway port. Implement per provider (Razorpay, Stripe, ...).
 */
public interface PaymentGatewayPort {

  String providerId();

  CreateCheckoutResult createCheckout(CreateCheckoutCommand command);

  VerifyPaymentResult verifyPayment(VerifyPaymentCommand command);

  WebhookHandleResult handleWebhook(WebhookHandleCommand command);

  /** Refunds {@code amount} of a captured payment; returns the provider's refund id. */
  default String refund(String providerPaymentId, BigDecimal amount, Map<String, String> notes) {
    throw new BaseException(ErrorCode.BUSINESS_VALIDATION_ERROR,
        "Refunds are not supported for provider " + providerId());
  }
}
