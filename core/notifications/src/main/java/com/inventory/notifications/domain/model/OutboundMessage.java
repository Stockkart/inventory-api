package com.inventory.notifications.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("outbound_messages")
public class OutboundMessage {

  @Id
  private String id;
  private MessageChannel channel;
  private String recipient;
  private String subject;
  private String body;
  private String templateId;       // Resend template ID or our EmailTemplate name
  private Map<String, Object> templateVariables;
  private Map<String, Object> metadata;

  private MessageStatus status;
  @Builder.Default
  private int retryCount = 0;
  private Instant lastAttemptAt;
  private Instant nextRetryAt;
  private String externalId;       // Provider's message ID (e.g. Resend)
  private String errorMessage;

  private Instant createdAt;
  private Instant sentAt;
}
