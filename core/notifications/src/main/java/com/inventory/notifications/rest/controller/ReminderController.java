package com.inventory.notifications.rest.controller;

import com.inventory.notifications.rest.dto.ReminderListResponse;
import com.inventory.notifications.rest.dto.ReminderResponse;
import com.inventory.notifications.rest.dto.SnoozeReminderRequest;
import com.inventory.notifications.service.ReminderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reminders")
public class ReminderController {

  @Autowired
  private ReminderService reminderService;

  @GetMapping
  public ResponseEntity<ReminderListResponse> list(@RequestParam String shopId) {
    return ResponseEntity.ok(reminderService.list(shopId));
  }

  @PostMapping("/{id}/snooze")
  public ResponseEntity<ReminderResponse> snooze(@PathVariable String id,
                                                 @RequestBody SnoozeReminderRequest request) {
    return ResponseEntity.ok(reminderService.snooze(id, request));
  }
}

