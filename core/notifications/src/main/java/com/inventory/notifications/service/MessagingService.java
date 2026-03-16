package com.inventory.notifications.service;

import com.inventory.notifications.domain.model.EmailTemplate;
import com.inventory.notifications.domain.model.MessageChannel;
import com.inventory.notifications.domain.model.MessageStatus;
import com.inventory.notifications.domain.model.OutboundMessage;
import com.inventory.notifications.domain.model.WhatsAppTemplate;
import com.inventory.notifications.domain.repository.OutboundMessageRepository;
import com.inventory.notifications.template.EmailTemplateContent;
import com.inventory.notifications.template.WhatsAppTemplateContent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
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
      log.debug("Enqueued email template={} to={} id={}", template, to, saved.getId());
    } catch (Exception e) {
      log.error("Failed to enqueue email to {}: {}", to, e.getMessage(), e);
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
      log.debug("Enqueued email to={} id={}", to, saved.getId());
    } catch (Exception e) {
      log.error("Failed to enqueue email to {}: {}", to, e.getMessage(), e);
    }
  }

  /**
   * Enqueue a WhatsApp message using a template. Processed asynchronously by the queue processor.
   * Recipient must be E.164 phone (e.g. +919876543210).
   *
   * @param toPhone  Recipient phone number (E.164)
   * @param template WhatsApp template
   * @param variables Template variables (e.g. userName, userEmail)
   */
  @Async
  public void sendWhatsAppWithTemplate(String toPhone, WhatsAppTemplate template, Map<String, Object> variables) {
    if (toPhone == null || toPhone.isBlank()) {
      log.warn("sendWhatsAppWithTemplate skipped: empty recipient");
      return;
    }

    String body = WhatsAppTemplateContent.getBody(template, variables != null ? variables : Map.of());
    Map<String, Object> vars = variables != null ? new HashMap<>(variables) : new HashMap<>();

    OutboundMessage message = OutboundMessage.builder()
        .channel(MessageChannel.WHATSAPP)
        .recipient(toPhone.trim().startsWith("+") ? toPhone.trim() : "+" + toPhone.trim())
        .subject(null)
        .body(body)
        .templateId(template.name())
        .templateVariables(vars)
        .metadata(Map.of("template", template.name()))
        .status(MessageStatus.PENDING)
        .retryCount(0)
        .createdAt(Instant.now())
        .build();

    try {
      OutboundMessage saved = outboundMessageRepository.save(message);
      log.debug("Enqueued WhatsApp template={} to={} id={}", template, toPhone, saved.getId());
    } catch (Exception e) {
      log.error("Failed to enqueue WhatsApp to {}: {}", toPhone, e.getMessage(), e);
    }
  }
}
