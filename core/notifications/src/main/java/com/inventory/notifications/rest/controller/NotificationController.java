package com.inventory.notifications.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.ValidationException;
import com.inventory.notifications.domain.model.NotificationChannel;
import com.inventory.notifications.domain.model.NotificationRecipient;
import com.inventory.notifications.rest.dto.SendNotificationRequest;
import com.inventory.notifications.service.NotificationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller for sending notifications.
 * Domain services typically inject NotificationService directly;
 * this controller is for testing and admin use.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

  @Autowired
  private NotificationService notificationService;

  @PostMapping("/send")
  public ResponseEntity<ApiResponse<Void>> send(@Valid @RequestBody SendNotificationRequest request) {
    validateRecipient(request);

    NotificationRecipient recipient = NotificationRecipient.builder()
        .email(request.getEmail())
        .phone(request.getPhone())
        .build();

    notificationService.sendAsync(
        request.getType(),
        request.getChannel(),
        recipient,
        request.getTemplateId(),
        request.getVariables() != null ? request.getVariables() : Map.of()
    );

    return ResponseEntity.accepted().body(ApiResponse.success(null));
  }

  private void validateRecipient(SendNotificationRequest request) {
    switch (request.getChannel()) {
      case EMAIL -> {
        if (!StringUtils.hasText(request.getEmail())) {
          throw new ValidationException("email is required for EMAIL channel");
        }
      }
      case SMS, WHATSAPP -> {
        if (!StringUtils.hasText(request.getPhone())) {
          throw new ValidationException("phone is required for " + request.getChannel() + " channel");
        }
      }
      default -> throw new ValidationException("Unsupported channel: " + request.getChannel());
    }
  }
}
