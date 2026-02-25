package com.inventory.notifications.rest.dto;

import com.inventory.notifications.domain.model.NotificationChannel;
import com.inventory.notifications.domain.model.NotificationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

/**
 * Request to send a notification.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendNotificationRequest {

  @NotNull
  private NotificationType type;

  @NotNull
  private NotificationChannel channel;

  private String email;
  private String phone;

  @NotBlank
  private String templateId;

  private Map<String, Object> variables;
}
