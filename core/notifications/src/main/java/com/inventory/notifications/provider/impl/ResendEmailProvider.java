package com.inventory.notifications.provider.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.notifications.exception.NotificationException;
import com.inventory.notifications.provider.EmailProvider;
import com.inventory.notifications.provider.dto.EmailMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Resend.com email provider.
 * Requires notification.email.provider=resend, RESEND_API_KEY and notification.email.from.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "notification.email.provider", havingValue = "resend")
public class ResendEmailProvider implements EmailProvider {

  private static final String RESEND_API_URL = "https://api.resend.com/emails";

  private final HttpClient httpClient;
  private final String apiKey;
  private final String fromAddress;
  private final ObjectMapper objectMapper;

  public ResendEmailProvider(
      @Value("${notification.email.api-key:${RESEND_API_KEY:}}") String apiKey,
      @Value("${notification.email.from:}") String fromAddress,
      ObjectMapper objectMapper) {
    this.apiKey = apiKey;
    this.fromAddress = fromAddress;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  @Override
  public void send(EmailMessage message) throws NotificationException {
    if (!StringUtils.hasText(apiKey)) {
      throw new NotificationException("Resend API key is required. Set notification.email.api-key or RESEND_API_KEY.");
    }
    if (!StringUtils.hasText(fromAddress)) {
      throw new NotificationException("notification.email.from is required for Resend.");
    }
    if (!StringUtils.hasText(message.getTo())) {
      throw new NotificationException("Recipient email (to) is required");
    }

    try {
      String body = buildRequestBody(message);
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(RESEND_API_URL))
          .header("Authorization", "Bearer " + apiKey)
          .header("Content-Type", "application/json")
          .timeout(Duration.ofSeconds(15))
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        log.debug("Email sent via Resend to {}", message.getTo());
        return;
      }

      String errorBody = response.body();
      log.warn("Resend API error status={} body={}", response.statusCode(), errorBody);
      throw new NotificationException("Resend send failed: " + response.statusCode() + " " + errorBody);
    } catch (NotificationException e) {
      throw e;
    } catch (Exception e) {
      log.warn("Resend send failed: {}", e.getMessage());
      throw new NotificationException("Resend send failed: " + e.getMessage(), e);
    }
  }

  @SuppressWarnings("unchecked")
  private String buildRequestBody(EmailMessage message) throws Exception {
    Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("from", fromAddress);
    payload.put("to", List.of(message.getTo()));
    payload.put("subject", message.getSubject() != null ? message.getSubject() : "(No subject)");

    if (StringUtils.hasText(message.getHtmlBody())) {
      payload.put("html", message.getHtmlBody());
      if (StringUtils.hasText(message.getBody())) {
        payload.put("text", message.getBody());
      }
    } else {
      payload.put("text", message.getBody() != null ? message.getBody() : "");
    }

    return objectMapper.writeValueAsString(payload);
  }
}
