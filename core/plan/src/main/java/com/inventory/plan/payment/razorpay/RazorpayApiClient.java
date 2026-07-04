package com.inventory.plan.payment.razorpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.common.exception.BaseException;
import com.inventory.common.constants.ErrorCode;
import com.inventory.plan.config.PaymentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class RazorpayApiClient {

  private static final String ORDERS_URL = "https://api.razorpay.com/v1/orders";
  private static final String PAYMENTS_URL_PREFIX = "https://api.razorpay.com/v1/payments/";

  private final PaymentProperties paymentProperties;
  private final ObjectMapper objectMapper;

  public RazorpayApiClient(PaymentProperties paymentProperties, ObjectMapper objectMapper) {
    this.paymentProperties = paymentProperties;
    this.objectMapper = objectMapper;
  }

  public JsonNode createOrder(String receipt, int amountPaise, String currency, Map<String, String> notes)
      throws IOException {
    Map<String, Object> body = new HashMap<>();
    body.put("amount", amountPaise);
    body.put("currency", currency);
    body.put("receipt", receipt);
    if (notes != null && !notes.isEmpty()) {
      body.put("notes", notes);
    }
    return post(ORDERS_URL, body);
  }

  public JsonNode fetchPayment(String paymentId) throws IOException {
    return get(PAYMENTS_URL_PREFIX + paymentId);
  }

  private JsonNode post(String url, Object body) throws IOException {
    byte[] payload = objectMapper.writeValueAsBytes(body);
    HttpURLConnection connection = openConnection(url, "POST");
    connection.setDoOutput(true);
    try (OutputStream os = connection.getOutputStream()) {
      os.write(payload);
    }
    return readResponse(connection);
  }

  private JsonNode get(String url) throws IOException {
    HttpURLConnection connection = openConnection(url, "GET");
    return readResponse(connection);
  }

  private HttpURLConnection openConnection(String url, String method) throws IOException {
    PaymentProperties.Razorpay razorpay = paymentProperties.getRazorpay();
    String keyId = razorpay.getKeyId();
    String keySecret = razorpay.getKeySecret();
    if (keyId == null || keyId.isBlank() || keySecret == null || keySecret.isBlank()) {
      throw new BaseException(
          ErrorCode.BUSINESS_VALIDATION_ERROR,
          "Razorpay is not configured. Set RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET.");
    }
    HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
    connection.setRequestMethod(method);
    connection.setConnectTimeout(15000);
    connection.setReadTimeout(15000);
    connection.setRequestProperty("Content-Type", "application/json");
    String auth = keyId.trim() + ":" + keySecret.trim();
    String encoded = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
    connection.setRequestProperty("Authorization", "Basic " + encoded);
    return connection;
  }

  private JsonNode readResponse(HttpURLConnection connection) throws IOException {
    int status = connection.getResponseCode();
    InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
    if (stream == null) {
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Empty Razorpay response");
    }
    JsonNode node = objectMapper.readTree(stream);
    if (status >= 400) {
      String message = node.path("error").path("description").asText("Razorpay request failed");
      log.warn("Razorpay API error {}: {}", status, message);
      if (status == 401) {
        throw new BaseException(ErrorCode.UNAUTHORIZED, "Razorpay authentication failed");
      }
      if (status >= 500) {
        throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, message);
      }
      throw new BaseException(ErrorCode.BUSINESS_VALIDATION_ERROR, message);
    }
    return node;
  }
}
