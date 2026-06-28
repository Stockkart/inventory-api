package com.inventory.plan.payment.razorpay;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public final class RazorpaySignatureVerifier {

  private RazorpaySignatureVerifier() {}

  public static boolean verifyCheckoutSignature(
      String orderId, String paymentId, String signature, String keySecret) {
    if (orderId == null || paymentId == null || signature == null || keySecret == null) {
      return false;
    }
    String payload = orderId + "|" + paymentId;
    String expected = hmacSha256Hex(payload, keySecret);
    return constantTimeEquals(expected, signature);
  }

  public static boolean verifyWebhookSignature(String rawBody, String signature, String webhookSecret) {
    if (rawBody == null || signature == null || webhookSecret == null) {
      return false;
    }
    String expected = hmacSha256Hex(rawBody, webhookSecret);
    return constantTimeEquals(expected, signature);
  }

  private static String hmacSha256Hex(String payload, String secret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to compute Razorpay signature", e);
    }
  }

  private static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null || a.length() != b.length()) {
      return false;
    }
    int result = 0;
    for (int i = 0; i < a.length(); i++) {
      result |= a.charAt(i) ^ b.charAt(i);
    }
    return result == 0;
  }
}
