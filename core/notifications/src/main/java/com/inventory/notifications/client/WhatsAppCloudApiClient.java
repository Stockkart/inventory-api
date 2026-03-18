package com.inventory.notifications.client;

import com.inventory.notifications.adapter.SendResult;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

/**
 * WhatsApp API wrapper using Meta's WhatsApp Cloud API directly.
 * Sends text messages via POST to graph.facebook.com.
 *
 * @see <a href="https://developers.facebook.com/docs/whatsapp/cloud-api/guides/send-messages">Send messages</a>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "messaging.whatsapp.enabled", havingValue = "true")
public class WhatsAppCloudApiClient implements WhatsAppApiClient {

  private static final String GRAPH_API_BASE = "https://graph.facebook.com/v21.0";

  @Value("${messaging.whatsapp.access-token:}")
  private String accessToken;

  @Value("${messaging.whatsapp.phone-number-id:}")
  private String phoneNumberId;

  private final RestTemplate restTemplate = new RestTemplate();
  private boolean initialized;

  @PostConstruct
  void init() {
    if (accessToken != null && !accessToken.isBlank() && phoneNumberId != null && !phoneNumberId.isBlank()) {
      initialized = true;
      log.info("WhatsApp Cloud API client initialized; phone_number_id={}", mask(phoneNumberId));
    } else {
      log.warn("WhatsApp Cloud API not configured: access-token and phone-number-id required");
    }
  }

  @Override
  public SendResult sendMessage(String toPhoneE164, String body) {
    if (!initialized) {
      return SendResult.failure("WhatsApp Cloud API client not initialized");
    }
    if (toPhoneE164 == null || toPhoneE164.isBlank()) {
      return SendResult.failure("Recipient phone is empty");
    }
    if (body == null || body.isBlank()) {
      return SendResult.failure("Message body is empty");
    }

    String to = normalizeTo(toPhoneE164);
    String url = GRAPH_API_BASE + "/" + phoneNumberId.trim() + "/messages";

    Map<String, Object> payload = Map.of(
        "messaging_product", "whatsapp",
        "recipient_type", "individual",
        "to", to,
        "type", "text",
        "text", Map.of(
            "preview_url", false,
            "body", body
        )
    );

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(accessToken.trim());

    try {
      ResponseEntity<CloudApiResponse> response = restTemplate.postForEntity(
          url,
          new HttpEntity<>(payload, headers),
          CloudApiResponse.class
      );

      if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
        String messageId = response.getBody().getMessageId();
        if (messageId != null) {
          return SendResult.success(messageId);
        }
      }
      return SendResult.failure("WhatsApp Cloud API returned no message id");
    } catch (org.springframework.web.client.RestClientException e) {
      log.warn("WhatsApp Cloud API send failed for {}: {}", to, e.getMessage());
      return SendResult.failure(e.getMessage());
    }
  }

  /**
   * Cloud API "to" field: digits only (no +). E.g. 16505551234.
   */
  private static String normalizeTo(String phone) {
    if (phone == null) return "";
    return phone.trim().replaceAll("^\\+", "").replaceAll("\\D", "");
  }

  private static String mask(String s) {
    if (s == null || s.length() < 4) return "***";
    return s.substring(0, 2) + "***" + s.substring(s.length() - 2);
  }

  /** Response from WhatsApp Cloud API POST /messages */
  @SuppressWarnings("unused")
  private static class CloudApiResponse {
    @JsonProperty("messages")
    private List<MessageEntry> messages;

    String getMessageId() {
      if (messages != null && !messages.isEmpty() && messages.get(0).id != null) {
        return messages.get(0).id;
      }
      return null;
    }

    private static class MessageEntry {
      @JsonProperty("id")
      private String id;
    }
  }
}
