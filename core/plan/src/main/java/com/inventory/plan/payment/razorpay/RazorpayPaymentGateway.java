package com.inventory.plan.payment.razorpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.common.exception.ValidationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.constants.ErrorCode;
import com.inventory.plan.config.PaymentProperties;
import com.inventory.plan.payment.PaymentGatewayPort;
import com.inventory.plan.payment.dto.CreateCheckoutCommand;
import com.inventory.plan.payment.dto.CreateCheckoutResult;
import com.inventory.plan.payment.dto.VerifyPaymentCommand;
import com.inventory.plan.payment.dto.VerifyPaymentResult;
import com.inventory.plan.payment.dto.WebhookHandleCommand;
import com.inventory.plan.payment.dto.WebhookHandleResult;
import com.inventory.plan.utils.constants.PlanPaymentConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class RazorpayPaymentGateway implements PaymentGatewayPort {

  private final PaymentProperties paymentProperties;
  private final RazorpayApiClient razorpayApiClient;
  private final ObjectMapper objectMapper;

  public RazorpayPaymentGateway(
      PaymentProperties paymentProperties,
      RazorpayApiClient razorpayApiClient,
      ObjectMapper objectMapper) {
    this.paymentProperties = paymentProperties;
    this.razorpayApiClient = razorpayApiClient;
    this.objectMapper = objectMapper;
  }

  @Override
  public String providerId() {
    return PlanPaymentConstants.PROVIDER_RAZORPAY;
  }

  @Override
  public CreateCheckoutResult createCheckout(CreateCheckoutCommand command) {
    int amountPaise = toPaise(command.getAmount());
    if (amountPaise < PlanPaymentConstants.MIN_AMOUNT_PAISE) {
      throw new ValidationException(
          "Amount must be at least " + PlanPaymentConstants.MIN_AMOUNT_PAISE + " paise");
    }
    Map<String, String> notes = new HashMap<>();
    notes.put("shopId", command.getShopId());
    notes.put("planId", command.getPlanId());
    notes.put("internalOrderId", command.getInternalOrderId());

    try {
      JsonNode response = razorpayApiClient.createOrder(
          "plan_" + command.getInternalOrderId(),
          amountPaise,
          command.getCurrency(),
          notes);
      return CreateCheckoutResult.builder()
          .providerOrderId(response.path("id").asText())
          .publicKey(paymentProperties.getRazorpay().getKeyId())
          .build();
    } catch (BaseException e) {
      throw e;
    } catch (Exception e) {
      log.error("Failed to create Razorpay order for internal order {}: {}", command.getInternalOrderId(), e.getMessage());
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to create payment order");
    }
  }

  @Override
  public VerifyPaymentResult verifyPayment(VerifyPaymentCommand command) {
    String keySecret = paymentProperties.getRazorpay().getKeySecret();
    boolean valid = RazorpaySignatureVerifier.verifyCheckoutSignature(
        command.getProviderOrderId(),
        command.getProviderPaymentId(),
        command.getSignature(),
        keySecret);
    if (!valid) {
      return VerifyPaymentResult.builder().valid(false).build();
    }
    String paymentMethod = fetchPaymentMethod(command.getProviderPaymentId());
    return VerifyPaymentResult.builder()
        .valid(true)
        .paymentMethod(paymentMethod)
        .build();
  }

  @Override
  public WebhookHandleResult handleWebhook(WebhookHandleCommand command) {
    String signature = command.getHeaders().get("x-razorpay-signature");
    if (signature == null) {
      signature = command.getHeaders().get("X-Razorpay-Signature");
    }
    String webhookSecret = paymentProperties.getRazorpay().getWebhookSecret();
    if (!RazorpaySignatureVerifier.verifyWebhookSignature(command.getRawBody(), signature, webhookSecret)) {
      log.warn("Invalid Razorpay webhook signature");
      return WebhookHandleResult.builder().processed(false).build();
    }

    try {
      JsonNode root = objectMapper.readTree(command.getRawBody());
      String event = root.path("event").asText();
      if (!"payment.captured".equals(event) && !"order.paid".equals(event)) {
        return WebhookHandleResult.builder().processed(false).build();
      }
      JsonNode paymentEntity = root.path("payload").path("payment").path("entity");
      if (paymentEntity.isMissingNode()) {
        return WebhookHandleResult.builder().processed(false).build();
      }
      return WebhookHandleResult.builder()
          .processed(true)
          .providerOrderId(textOrNull(paymentEntity.path("order_id")))
          .providerPaymentId(textOrNull(paymentEntity.path("id")))
          .paymentMethod(paymentEntity.path("method").asText("razorpay"))
          .build();
    } catch (Exception e) {
      log.error("Failed to parse Razorpay webhook: {}", e.getMessage());
      return WebhookHandleResult.builder().processed(false).build();
    }
  }

  private String fetchPaymentMethod(String paymentId) {
    try {
      JsonNode payment = razorpayApiClient.fetchPayment(paymentId);
      return payment.path("method").asText("razorpay");
    } catch (Exception e) {
      log.debug("Could not fetch Razorpay payment {}: {}", paymentId, e.getMessage());
      return "razorpay";
    }
  }

  private static int toPaise(BigDecimal amountInr) {
    return amountInr.multiply(BigDecimal.valueOf(100))
        .setScale(0, RoundingMode.HALF_UP)
        .intValueExact();
  }

  private static String textOrNull(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    String value = node.asText();
    return value.isBlank() ? null : value;
  }
}
