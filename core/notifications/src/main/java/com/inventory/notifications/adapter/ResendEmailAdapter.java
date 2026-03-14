package com.inventory.notifications.adapter;

import com.inventory.notifications.domain.model.OutboundMessage;
import com.inventory.notifications.utils.MessagingUtils;
import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnProperty(name = "messaging.email.enabled", havingValue = "true")
public class ResendEmailAdapter implements ChannelAdapter {

  @Value("${messaging.email.api-key:}")
  private String apiKey;

  @Value("${messaging.email.from-email:notifications@example.com}")
  private String fromEmail;

  @Value("${messaging.email.from-name:Stock Kart}")
  private String fromName;

  private Resend resend;

  @PostConstruct
  void init() {
    if (apiKey != null && !apiKey.isBlank()) {
      this.resend = new Resend(apiKey);
    } else {
      log.warn("Resend API key not configured - email adapter will fail");
    }
  }

  @Override
  public SendResult send(OutboundMessage message) {
    if (resend == null) {
      return SendResult.failure("Resend client not initialized");
    }

    try {
      String to = message.getRecipient();
      String subject = message.getSubject();
      String html = resolveBody(message);

      if (html == null || html.isBlank()) {
        return SendResult.failure("Email body is empty");
      }

      CreateEmailOptions params = CreateEmailOptions.builder()
          .from(fromName + " <" + fromEmail + ">")
          .to(to)
          .subject(subject != null ? subject : "Notification")
          .html(html)
          .build();

      CreateEmailResponse response = resend.emails().send(params);

      if (response != null && response.getId() != null) {
        return SendResult.success(response.getId());
      }
      return SendResult.failure("Resend returned no message ID");

    } catch (ResendException e) {
      log.warn("Resend send failed for {}: {}", message.getRecipient(), e.getMessage());
      return SendResult.failure(e.getMessage());
    } catch (Exception e) {
      log.error("Unexpected error sending email to {}: {}", message.getRecipient(), e.getMessage(), e);
      return SendResult.failure(e.getMessage());
    }
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
  public com.inventory.notifications.domain.model.MessageChannel getChannel() {
    return com.inventory.notifications.domain.model.MessageChannel.EMAIL;
  }
}
