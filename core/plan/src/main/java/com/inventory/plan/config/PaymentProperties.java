package com.inventory.plan.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "payment")
public class PaymentProperties {

  /** Active gateway: razorpay, stripe, ... */
  private String gateway = "razorpay";

  private Razorpay razorpay = new Razorpay();

  @Data
  public static class Razorpay {
    private String keyId;
    private String keySecret;
    private String webhookSecret;
  }
}
