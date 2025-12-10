package com.inventory.notifications.domain.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("events")
public class ReminderEvent {

    @Id
    private String id;
    private String reminderId;
    private String shopId;
    private ReminderType type;
    private ReminderStatus statusAtTrigger;
    private Instant triggeredAt;
    private String notes;         // optional, from reminder
    private boolean delivered;    // true when SSE delivered successfully
    private Instant deliveredAt;
    @Builder.Default
    private int retryCount = 0;   // for future retries, if needed
}
