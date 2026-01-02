package com.inventory.notifications.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.notifications.rest.dto.InventoryLowEventDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.inventory.notifications.service.EventService;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

  @Autowired
  private EventService eventService;

  @GetMapping("/stream")
  public SseEmitter stream(HttpServletRequest httpRequest) {
    // Get shopId from request attributes (set by AuthenticationInterceptor)
    String shopId = (String) httpRequest.getAttribute("shopId");

    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(
        ErrorCode.UNAUTHORIZED,
        "Unauthorized access to shop reminders stream");
    }
    return eventService.subscribe(shopId);
  }

}