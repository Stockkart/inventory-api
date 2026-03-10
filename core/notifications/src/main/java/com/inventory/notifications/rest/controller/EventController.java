package com.inventory.notifications.rest.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.inventory.notifications.service.EventService;
import com.inventory.notifications.validation.ReminderValidator;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

  @Autowired
  private EventService eventService;

  @Autowired
  private ReminderValidator reminderValidator;

  @GetMapping("/stream")
  public SseEmitter stream(HttpServletRequest httpRequest) {
    // Get shopId from request attributes (set by AuthenticationInterceptor)
    String shopId = (String) httpRequest.getAttribute("shopId");

    reminderValidator.validateShopId(shopId);
    return eventService.subscribe(shopId);
  }

}