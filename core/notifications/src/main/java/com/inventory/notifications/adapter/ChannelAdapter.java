package com.inventory.notifications.adapter;

import com.inventory.notifications.domain.model.MessageChannel;
import com.inventory.notifications.domain.model.OutboundMessage;

/**
 * Adapter for sending messages through a specific channel (email, WhatsApp, etc.).
 */
public interface ChannelAdapter {

  SendResult send(OutboundMessage message);

  MessageChannel getChannel();
}
