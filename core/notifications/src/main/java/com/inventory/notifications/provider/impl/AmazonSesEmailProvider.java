package com.inventory.notifications.provider.impl;

import com.inventory.notifications.exception.NotificationException;
import com.inventory.notifications.provider.EmailProvider;
import com.inventory.notifications.provider.dto.EmailMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.*;

/**
 * Amazon SES email provider.
 * Requires notification.email.provider=ses and notification.email.from (verified sender).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "notification.email.provider", havingValue = "aws-ses")
public class AmazonSesEmailProvider implements EmailProvider {

  private final SesClient sesClient;
  private final String fromAddress;

  public AmazonSesEmailProvider(SesClient sesClient,
      @Value("${notification.email.from:}") String fromAddress) {
    this.sesClient = sesClient;
    this.fromAddress = fromAddress;
  }

  @Override
  public void send(EmailMessage message) throws NotificationException {
    if (!StringUtils.hasText(fromAddress)) {
      throw new NotificationException("notification.email.from is required for Amazon SES. " +
          "Use a verified email or domain in SES.");
    }
    if (!StringUtils.hasText(message.getTo())) {
      throw new NotificationException("Recipient email (to) is required");
    }

    try {
      Content subject = Content.builder()
          .data(message.getSubject() != null ? message.getSubject() : "(No subject)")
          .build();

      Body body;
      if (StringUtils.hasText(message.getHtmlBody())) {
        body = Body.builder()
            .html(Content.builder().data(message.getHtmlBody()).build())
            .text(StringUtils.hasText(message.getBody()) ? Content.builder().data(message.getBody()).build() : null)
            .build();
      } else {
        body = Body.builder()
            .text(Content.builder()
                .data(message.getBody() != null ? message.getBody() : "")
                .build())
            .build();
      }

      Message sesMessage = Message.builder()
          .subject(subject)
          .body(body)
          .build();

      SendEmailRequest request = SendEmailRequest.builder()
          .source(fromAddress)
          .destination(Destination.builder().toAddresses(message.getTo()).build())
          .message(sesMessage)
          .build();

      sesClient.sendEmail(request);
      log.debug("Email sent via SES to {}", message.getTo());
    } catch (SesException e) {
      log.warn("SES send failed: {}", e.awsErrorDetails().errorMessage());
      throw new NotificationException("SES send failed: " + e.awsErrorDetails().errorMessage(), e);
    }
  }
}
