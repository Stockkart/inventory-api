package com.inventory.notifications.provider.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.notifications.exception.NotificationException;
import com.inventory.notifications.provider.WhatsAppProvider;
import com.inventory.notifications.provider.dto.WhatsAppMessage;
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
import java.util.Map;

/**
 * Meta WhatsApp Cloud API provider.
 * Requires notification.whatsapp.provider=meta, token and phone number id.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "notification.whatsapp.provider", havingValue = "meta")
public class MetaWhatsAppProvider implements WhatsAppProvider {

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final String accessToken;
  private final String phoneNumberId;
  private final String apiBaseUrl;

  public MetaWhatsAppProvider(
      @Value("${notification.whatsapp.api-token:${WHATSAPP_API_TOKEN:}}") String accessToken,
      @Value("${notification.whatsapp.phone-number-id:${WHATSAPP_PHONE_NUMBER_ID:}}") String phoneNumberId,
      @Value("${notification.whatsapp.api-base-url:https://graph.facebook.com/v22.0}") String apiBaseUrl,
      ObjectMapper objectMapper) {
    this.accessToken = accessToken;
    this.phoneNumberId = phoneNumberId;
    this.apiBaseUrl = apiBaseUrl;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  @Override
  public void send(WhatsAppMessage message) throws NotificationException {
    if (!StringUtils.hasText(accessToken)) {
      throw new NotificationException("WhatsApp API token is required. Set notification.whatsapp.api-token or WHATSAPP_API_TOKEN.");
    }
    if (!StringUtils.hasText(phoneNumberId)) {
      throw new NotificationException("WhatsApp phone number id is required. Set notification.whatsapp.phone-number-id or WHATSAPP_PHONE_NUMBER_ID.");
    }
    if (!StringUtils.hasText(message.getTo())) {
      throw new NotificationException("Recipient phone (to) is required for WhatsApp.");
    }

    try {
      String to = normalizePhone(message.getTo());
      String url = apiBaseUrl + "/" + phoneNumberId + "/messages";

      Map<String, Object> payload = Map.of(
          "messaging_product", "whatsapp",
          "to", to,
          "type", "text",
          "text", Map.of("body", message.getBody() != null ? message.getBody() : "")
      );

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .header("Authorization", "Bearer " + accessToken)
          .header("Content-Type", "application/json")
          .timeout(Duration.ofSeconds(15))
          .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
          .build();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        log.debug("WhatsApp message sent via Meta to {}", to);
        return;
      }

      log.warn("Meta WhatsApp API error status={} body={}", response.statusCode(), response.body());
      throw new NotificationException("Meta WhatsApp send failed: " + response.statusCode() + " " + response.body());
    } catch (NotificationException e) {
      throw e;
    } catch (Exception e) {
      throw new NotificationException("Meta WhatsApp send failed: " + e.getMessage(), e);
    }
  }

  private String normalizePhone(String phone) {
    return phone.replaceAll("[^0-9]", "");
  }
}
