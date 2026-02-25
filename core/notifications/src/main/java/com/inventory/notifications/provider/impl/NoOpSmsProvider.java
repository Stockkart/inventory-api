package com.inventory.notifications.provider.impl;

import com.inventory.notifications.exception.NotificationException;
import com.inventory.notifications.provider.SmsProvider;
import com.inventory.notifications.provider.dto.SmsMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * No-op SMS provider when no real provider (e.g. Twilio) is configured.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "notification.sms.provider", havingValue = "noop", matchIfMissing = true)
public class NoOpSmsProvider implements SmsProvider {

  @Override
  public void send(SmsMessage message) throws NotificationException {
    log.debug("NoOpSmsProvider: would send SMS to {} body={}", message.getTo(), message.getBody());
  }
}
