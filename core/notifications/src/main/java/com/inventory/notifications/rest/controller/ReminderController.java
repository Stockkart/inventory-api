package com.inventory.notifications.rest.controller;

import com.inventory.notifications.rest.dto.ReminderListResponse;
import com.inventory.notifications.rest.dto.ReminderResponse;
import com.inventory.notifications.rest.dto.SnoozeReminderRequest;
import com.inventory.notifications.service.ReminderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reminders")
@RequiredArgsConstructor
public class ReminderController {

    private final ReminderService reminderService;

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

