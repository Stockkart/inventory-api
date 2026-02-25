package com.inventory.notifications.provider;

import com.inventory.notifications.exception.NotificationException;
import com.inventory.notifications.provider.dto.EmailMessage;

/**
 * Abstraction for email delivery.
 * Implementations: SendGridEmailProvider, AmazonSesEmailProvider, etc.
 */
public interface EmailProvider {

  void send(EmailMessage message) throws NotificationException;
}
