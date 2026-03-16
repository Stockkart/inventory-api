package com.inventory.notifications.client;

import com.inventory.notifications.adapter.SendResult;

/**
 * Wrapper for WhatsApp sending APIs (e.g. Twilio, WhatsApp Business API).
 * Implementations send a text message to a phone number in E.164 format.
 */
public interface WhatsAppApiClient {

  /**
   * Send a text message to the given phone number.
   *
   * @param toPhoneE164 Recipient phone in E.164 format (e.g. +919876543210)
   * @param body        Plain text message body
   * @return SendResult with success/failure and optional provider message ID
   */
  SendResult sendMessage(String toPhoneE164, String body);
}
