package com.inventory.product.service;

import com.inventory.product.rest.dto.inventory.InventoryLowEventDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationAdapter {

  private final RestTemplate restTemplate = new RestTemplate();

  @Value("${notifications.service.url}")
  private String notificationsBaseUrl;

  public void sendInventoryLowEvent(InventoryLowEventDto dto) {
    try {
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);

      HttpEntity<InventoryLowEventDto> request = new HttpEntity<>(dto, headers);

      restTemplate.postForEntity(
        notificationsBaseUrl + "/api/v1/events/inventory-low",
        request,
        Void.class
      );

      log.info("Sent INVENTORY_LOW event for inventory {}", dto.getInventoryId());

    } catch (Exception e) {
      log.error("Failed sending inventory low event", e);
    }
  }
}
