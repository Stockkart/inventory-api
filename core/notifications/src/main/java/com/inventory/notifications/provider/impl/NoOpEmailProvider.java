package com.inventory.notifications.provider.impl;

import com.inventory.notifications.exception.NotificationException;
import com.inventory.notifications.provider.EmailProvider;
import com.inventory.notifications.provider.dto.EmailMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * No-op email provider when no real provider (e.g. SendGrid) is configured.
 * Use for local development. Configure a real provider (SendGrid, SES) for production.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "notification.email.provider", havingValue = "noop", matchIfMissing = true)
public class NoOpEmailProvider implements EmailProvider {

  @Override
  public void send(EmailMessage message) throws NotificationException {
    log.debug("NoOpEmailProvider: would send email to {} subject={}", message.getTo(), message.getSubject());
    // Simulate success - no actual send
  }
}
