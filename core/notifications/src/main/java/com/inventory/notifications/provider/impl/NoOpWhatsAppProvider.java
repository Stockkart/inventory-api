package com.inventory.notifications.provider.impl;

import com.inventory.notifications.exception.NotificationException;
import com.inventory.notifications.provider.WhatsAppProvider;
import com.inventory.notifications.provider.dto.WhatsAppMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * No-op WhatsApp provider when no real provider (e.g. Meta Cloud API) is configured.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "notification.whatsapp.provider", havingValue = "noop", matchIfMissing = true)
public class NoOpWhatsAppProvider implements WhatsAppProvider {

  @Override
  public void send(WhatsAppMessage message) throws NotificationException {
    log.debug("NoOpWhatsAppProvider: would send WhatsApp to {} body={}", message.getTo(), message.getBody());
  }
}
