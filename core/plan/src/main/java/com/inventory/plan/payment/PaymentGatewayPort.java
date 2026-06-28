package com.inventory.plan.payment;

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
}
