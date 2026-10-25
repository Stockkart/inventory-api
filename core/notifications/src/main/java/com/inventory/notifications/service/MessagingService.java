package com.inventory.notifications.service;

import com.inventory.notifications.domain.model.EmailTemplate;
import com.inventory.notifications.domain.model.MessageChannel;
import com.inventory.notifications.domain.model.MessageStatus;
import com.inventory.notifications.domain.model.OutboundMessage;
import com.inventory.notifications.domain.repository.OutboundMessageRepository;
import com.inventory.notifications.template.EmailTemplateContent;
import com.inventory.notifications.utils.MessagingUtils;
import com.inventory.notifications.utils.constants.NotificationMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Public API for sending notifications (email, WhatsApp). Messages are enqueued
 * and processed asynchronously by the scheduler with retry support.
 */
@Slf4j
@Service
public class MessagingService {

  @Autowired
  private OutboundMessageRepository outboundMessageRepository;

  @Autowired
  private MetricsWrapper metrics;

  /**
   * Enqueue an email using a template. Processed asynchronously by the queue processor.
   *
   * @param to       Recipient email
   * @param template Template identifier
   * @param variables Template variables (e.g. userName, userEmail)
   */
  @Async
  public void sendEmailWithTemplate(String to, EmailTemplate template, Map<String, Object> variables) {
    if (to == null || to.isBlank()) {
      log.warn("sendEmailWithTemplate skipped: empty recipient");
      return;
    }

    EmailTemplateContent.TemplateContent content = EmailTemplateContent.get(template, variables != null ? variables : Map.of());
    Map<String, Object> vars = variables != null ? new HashMap<>(variables) : new HashMap<>();

    OutboundMessage message = OutboundMessage.builder()
        .channel(MessageChannel.EMAIL)
        .recipient(to.trim())
        .subject(content.subject())
        .body(content.htmlBody())
        .templateId(template.name())
        .templateVariables(vars)
        .metadata(Map.of("template", template.name()))
        .status(MessageStatus.PENDING)
        .retryCount(0)
        .createdAt(Instant.now())
        .build();

    try {
      OutboundMessage saved = outboundMessageRepository.save(message);
      log.debug("Enqueued email template={} id={}", template, saved.getId());
      recordEnqueued("email", "success");
    } catch (Exception e) {
      log.error("Failed to enqueue email template={}: {}", template, e.getMessage(), e);
      recordEnqueued("email", "error");
    }
  }

  /**
   * Enqueue a plain email (subject + HTML body). Processed asynchronously.
   */
  @Async
  public void sendEmail(String to, String subject, String htmlBody, Map<String, Object> metadata) {
    if (to == null || to.isBlank()) {
      log.warn("sendEmail skipped: empty recipient");
      return;
    }

    OutboundMessage message = OutboundMessage.builder()
        .channel(MessageChannel.EMAIL)
        .recipient(to.trim())
        .subject(subject != null ? subject : "Notification")
        .body(htmlBody != null ? htmlBody : "")
        .metadata(metadata != null ? metadata : Map.of())
        .status(MessageStatus.PENDING)
        .retryCount(0)
        .createdAt(Instant.now())
        .build();

    try {
      OutboundMessage saved = outboundMessageRepository.save(message);
      log.debug("Enqueued email id={}", saved.getId());
      recordEnqueued("email", "success");
    } catch (Exception e) {
      log.error("Failed to enqueue email: {}", e.getMessage(), e);
      recordEnqueued("email", "error");
    }
  }

  /**
   * Queues template emails whose attachment is rendered at send time (HR payslips, decision 1),
   * in one insert. Synchronous so the caller can report how many were queued. Recipients are
   * never logged.
   *
   * @param emails one entry per recipient; entries without a recipient are skipped
   * @return messages queued
   */
  public int enqueueTemplateEmails(EmailTemplate template, List<TemplateEmail> emails) {
    List<OutboundMessage> messages = new ArrayList<>();
    Instant now = Instant.now();
    for (TemplateEmail email : emails) {
      if (email.to() == null || email.to().isBlank()) {
        continue;
      }
      Map<String, Object> vars =
          email.variables() != null ? new HashMap<>(email.variables()) : new HashMap<>();
      EmailTemplateContent.TemplateContent content = EmailTemplateContent.get(template, vars);
      messages.add(OutboundMessage.builder()
          .channel(MessageChannel.EMAIL)
          .recipient(email.to().trim())
          .subject(MessagingUtils.resolveTemplate(content.subject(), stringVars(vars)))
          .body(content.htmlBody())
          .templateId(template.name())
          .templateVariables(vars)
          .metadata(Map.of("template", template.name()))
          .attachmentRef(email.attachmentRef())
          .status(MessageStatus.PENDING)
          .retryCount(0)
          .createdAt(now)
          .build());
    }
    if (messages.isEmpty()) {
      return 0;
    }
    try {
      // all new: Spring Data sends one insertMany
      outboundMessageRepository.saveAll(messages);
      log.debug("Enqueued {} email(s) template={}", messages.size(), template);
      metrics.record(NotificationMetricsConstants.ENQUEUED_TOTAL, messages.size(),
          "module", NotificationMetricsConstants.MODULE, "channel", "email", "outcome", "success");
      return messages.size();
    } catch (RuntimeException e) {
      log.error("Failed to enqueue {} email(s) template={}: {}", messages.size(), template,
          e.getClass().getSimpleName());
      recordEnqueued("email", "error");
      throw e;
    }
  }

  /** One recipient of {@link #enqueueTemplateEmails}. */
  public record TemplateEmail(String to, Map<String, Object> variables,
      OutboundMessage.AttachmentRef attachmentRef) {}

  private static Map<String, Object> stringVars(Map<String, Object> vars) {
    Map<String, Object> out = new HashMap<>();
    vars.forEach((k, v) -> out.put(k, v != null ? v.toString() : ""));
    return out;
  }

  private void recordEnqueued(String channel, String outcome) {
    metrics.record(
        NotificationMetricsConstants.ENQUEUED_TOTAL,
        1,
        "module",
        NotificationMetricsConstants.MODULE,
        "channel",
        channel,
        "outcome",
        outcome);
  }
}
