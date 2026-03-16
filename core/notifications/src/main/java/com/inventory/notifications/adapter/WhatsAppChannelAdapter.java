package com.inventory.notifications.adapter;

import com.inventory.notifications.client.WhatsAppApiClient;
import com.inventory.notifications.domain.model.MessageChannel;
import com.inventory.notifications.domain.model.OutboundMessage;
import com.inventory.notifications.utils.MessagingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnProperty(name = "messaging.whatsapp.enabled", havingValue = "true")
public class WhatsAppChannelAdapter implements ChannelAdapter {

  @Autowired
  private WhatsAppApiClient whatsAppApiClient;

  @Override
  public SendResult send(OutboundMessage message) {
    if (whatsAppApiClient == null) {
      return SendResult.failure("WhatsApp client not available");
    }

    String to = message.getRecipient();
    String body = resolveBody(message);

    if (body == null || body.isBlank()) {
      return SendResult.failure("WhatsApp message body is empty");
    }

    return whatsAppApiClient.sendMessage(to, body);
  }

  private String resolveBody(OutboundMessage message) {
    String body = message.getBody();
    Map<String, Object> vars = message.getTemplateVariables();

    if (vars != null && !vars.isEmpty() && body != null) {
      Map<String, Object> stringVars = vars.entrySet().stream()
          .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue() != null ? e.getValue().toString() : ""));
      return MessagingUtils.resolveTemplate(body, stringVars);
    }
    return body;
  }

  @Override
  public MessageChannel getChannel() {
    return MessageChannel.WHATSAPP;
  }
}
