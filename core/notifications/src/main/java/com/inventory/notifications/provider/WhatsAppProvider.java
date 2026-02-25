package com.inventory.notifications.provider;

import com.inventory.notifications.exception.NotificationException;
import com.inventory.notifications.provider.dto.WhatsAppMessage;

/**
 * Abstraction for WhatsApp delivery.
 * Implementations: MetaWhatsAppProvider, TwilioWhatsAppProvider, etc.
 */
public interface WhatsAppProvider {

  void send(WhatsAppMessage message) throws NotificationException;
}
