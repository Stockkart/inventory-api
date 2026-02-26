package com.inventory.user.util;

import com.inventory.notifications.domain.model.NotificationChannel;
import com.inventory.notifications.domain.model.NotificationRecipient;
import com.inventory.notifications.domain.model.NotificationType;
import com.inventory.notifications.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Helper for auth-related email notifications.
 * Wraps NotificationService to send login/signup etc. emails.
 */
@Component
public class AuthNotificationHelper {

  @Autowired(required = false)
  private NotificationService notificationService;

  @Value("${notification.whatsapp.login-to:}")
  private String loginWhatsAppTo;

  /**
   * Sends a simple login success email to the given address.
   */
  public void sendLoginSuccess(String email) {
    if (notificationService == null || !StringUtils.hasText(email)) {
      return;
    }
    notificationService.sendAsync(
        NotificationType.ALERT,
        NotificationChannel.EMAIL,
        NotificationRecipient.builder().email(email).build(),
        "login_success",
        Map.of("subject", "Login successful", "body", "You have successfully logged in to StockKart.")
    );

    // For quick testing, WhatsApp login alert is sent to a configured phone number.
    if (StringUtils.hasText(loginWhatsAppTo)) {
      notificationService.sendAsync(
          NotificationType.ALERT,
          NotificationChannel.WHATSAPP,
          NotificationRecipient.builder().phone("+918800107393").build(),
          "hello_world",
          Map.of("body", "Login successful on StockKart for " + email)
      );
    }
  }
}
