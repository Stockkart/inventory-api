package com.inventory.notifications.service;

import com.inventory.notifications.domain.model.*;
import com.inventory.notifications.domain.repository.NotificationJobRepository;
import com.inventory.notifications.exception.NotificationException;
import com.inventory.notifications.provider.EmailProvider;
import com.inventory.notifications.provider.SmsProvider;
import com.inventory.notifications.provider.WhatsAppProvider;
import com.inventory.notifications.provider.dto.EmailMessage;
import com.inventory.notifications.provider.dto.SmsMessage;
import com.inventory.notifications.provider.dto.WhatsAppMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Orchestrates notification sending. No provider-specific logic or direct DB queries.
 */
@Slf4j
@Service
public class NotificationService {

  @Value("${notification.retry.base-delay-minutes:5}")
  private int baseDelayMinutes;

  @Value("${notification.retry.max-retries:3}")
  private int defaultMaxRetries;

  @Autowired
  private NotificationJobRepository notificationJobRepository;

  @Autowired
  private TemplateEngine templateEngine;

  @Autowired(required = false)
  private EmailProvider emailProvider;

  @Autowired(required = false)
  private SmsProvider smsProvider;

  @Autowired(required = false)
  private WhatsAppProvider whatsAppProvider;

  /**
   * Send notification asynchronously (non-blocking API).
   */
  @Async("notificationExecutor")
  public void sendAsync(NotificationType type, NotificationChannel channel,
      NotificationRecipient recipient, String templateId, Map<String, Object> variables) {
    NotificationJob job = buildJob(type, channel, recipient, templateId, variables);
    notificationJobRepository.save(job);
    sendNotificationAsync(job);
  }

  /**
   * Send notification synchronously (e.g. for retries).
   */
  public void sendNotificationAsync(NotificationJob job) {
    try {
      dispatchToProvider(job);
      job.setStatus(NotificationStatus.SUCCESS);
      job.setLastError(null);
      job.setUpdatedAt(Instant.now());
      notificationJobRepository.save(job);
      log.info("Notification sent successfully jobId={} channel={}", job.getId(), job.getChannel());
    } catch (NotificationException e) {
      handleFailure(job, e);
    }
  }

  private NotificationJob buildJob(NotificationType type, NotificationChannel channel,
      NotificationRecipient recipient, String templateId, Map<String, Object> variables) {
    Instant now = Instant.now();
    return NotificationJob.builder()
        .type(type)
        .channel(channel)
        .recipient(recipient)
        .payload(NotificationPayload.builder()
            .templateId(templateId)
            .variables(variables != null ? variables : Map.of())
            .build())
        .status(NotificationStatus.PENDING)
        .retryCount(0)
        .maxRetries(defaultMaxRetries)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }

  private void dispatchToProvider(NotificationJob job) throws NotificationException {
    switch (job.getChannel()) {
      case EMAIL -> dispatchEmail(job);
      case SMS -> dispatchSms(job);
      case WHATSAPP -> dispatchWhatsApp(job);
      default -> throw new NotificationException("Unsupported channel: " + job.getChannel());
    }
  }

  private void dispatchEmail(NotificationJob job) throws NotificationException {
    if (emailProvider == null) {
      throw new NotificationException("Email provider not configured");
    }
    String body = templateEngine.render(job.getPayload().getTemplateId(), job.getPayload().getVariables());
    String subject = templateEngine.renderSubject(job.getPayload().getTemplateId(), job.getPayload().getVariables());
    EmailMessage msg = EmailMessage.builder()
        .to(job.getRecipient().getEmail())
        .subject(subject)
        .body(body)
        .htmlBody(body)
        .build();
    emailProvider.send(msg);
  }

  private void dispatchSms(NotificationJob job) throws NotificationException {
    if (smsProvider == null) {
      throw new NotificationException("SMS provider not configured");
    }
    String body = templateEngine.render(job.getPayload().getTemplateId(), job.getPayload().getVariables());
    SmsMessage msg = SmsMessage.builder()
        .to(job.getRecipient().getPhone())
        .body(body)
        .build();
    smsProvider.send(msg);
  }

  private void dispatchWhatsApp(NotificationJob job) throws NotificationException {
    if (whatsAppProvider == null) {
      throw new NotificationException("WhatsApp provider not configured");
    }
    String body = templateEngine.render(job.getPayload().getTemplateId(), job.getPayload().getVariables());
    WhatsAppMessage msg = WhatsAppMessage.builder()
        .to(job.getRecipient().getPhone())
        .body(body)
        .build();
    whatsAppProvider.send(msg);
  }

  private void handleFailure(NotificationJob job, NotificationException e) {
    int newRetryCount = job.getRetryCount() + 1;
    job.setRetryCount(newRetryCount);
    job.setStatus(NotificationStatus.FAILED);
    job.setLastError(e.getMessage());
    job.setUpdatedAt(Instant.now());

    if (newRetryCount < job.getMaxRetries()) {
      long delayMinutes = (long) (baseDelayMinutes * Math.pow(2, job.getRetryCount()));
      job.setNextRetryAt(Instant.now().plusSeconds(delayMinutes * 60L));
      log.warn("Notification failed jobId={} retryCount={} nextRetryAt={} error={}",
          job.getId(), newRetryCount, job.getNextRetryAt(), e.getMessage());
    } else {
      job.setNextRetryAt(null);
      log.error("Notification failed permanently jobId={} maxRetries exceeded error={}",
          job.getId(), e.getMessage());
    }
    notificationJobRepository.save(job);
  }
}
