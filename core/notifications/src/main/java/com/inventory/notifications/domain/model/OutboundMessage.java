package com.inventory.notifications.domain.model;

import com.inventory.notifications.api.EmailTemplate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
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

  /**
   * Files sent with an email. Cleared once the message is SENT so file bytes do not stay in the
   * collection; never filled for messages with an {@link #attachmentRef} (those are rendered at
   * send time and not stored). null or empty = no attachment.
   */
  private List<Attachment> attachments;

  /**
   * A document to render and attach when the message is sent (HR decision 1: payslips are
   * rendered by an {@code AttachmentPort} at send time, at most 10 per dispatch tick).
   * null = nothing to render.
   */
  private AttachmentRef attachmentRef;

  /** One file of an email. Getters only for the bytes: no toString, so they never reach a log. */
  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Attachment {
    private String fileName;
    private String contentType;
    /** File bytes, base64 encoded (the form Resend takes). */
    private String base64;
  }

  /** What to render at send time; ids only, never names, amounts or addresses. */
  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  public static class AttachmentRef {
    /** Resolver key, e.g. {@code PAYSLIP}. */
    private String kind;
    private String runId;
    private String lineId;
    private String shopId;
  }
}
