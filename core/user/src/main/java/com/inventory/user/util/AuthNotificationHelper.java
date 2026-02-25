package com.inventory.user.util;

import com.inventory.notifications.domain.model.NotificationChannel;
import com.inventory.notifications.domain.model.NotificationRecipient;
import com.inventory.notifications.domain.model.NotificationType;
import com.inventory.notifications.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
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

  /**
   * Sends a simple login success email to the given address.
   */
  public void sendLoginSuccessEmail(String email) {
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
  }
}
