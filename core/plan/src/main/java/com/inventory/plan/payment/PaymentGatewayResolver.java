package com.inventory.plan.payment;

import com.inventory.common.exception.ValidationException;
import com.inventory.plan.config.PaymentProperties;
import com.inventory.plan.utils.constants.PlanPaymentConstants;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class PaymentGatewayResolver {

  private final PaymentProperties paymentProperties;
  private final Map<String, PaymentGatewayPort> gatewaysById;

  public PaymentGatewayResolver(PaymentProperties paymentProperties, List<PaymentGatewayPort> gateways) {
    this.paymentProperties = paymentProperties;
    this.gatewaysById = gateways.stream()
        .collect(Collectors.toMap(PaymentGatewayPort::providerId, Function.identity()));
  }

  public PaymentGatewayPort resolve() {
    String gatewayId = paymentProperties.getGateway();
    PaymentGatewayPort gateway = gatewaysById.get(gatewayId);
    if (gateway == null) {
      throw new ValidationException("Unsupported payment gateway: " + gatewayId);
    }
    return gateway;
  }

  public String activeProviderId() {
    return paymentProperties.getGateway();
  }

  public String activePublicKey() {
    PaymentGatewayPort gateway = resolve();
    if (PlanPaymentConstants.PROVIDER_RAZORPAY.equals(gateway.providerId())) {
      return paymentProperties.getRazorpay().getKeyId();
    }
    return null;
  }
}
