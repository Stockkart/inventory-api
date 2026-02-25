package com.inventory.notifications.provider;

import com.inventory.notifications.exception.NotificationException;
import com.inventory.notifications.provider.dto.SmsMessage;

/**
 * Abstraction for SMS delivery.
 * Implementations: TwilioSmsProvider, etc.
 */
public interface SmsProvider {

  void send(SmsMessage message) throws NotificationException;
}
