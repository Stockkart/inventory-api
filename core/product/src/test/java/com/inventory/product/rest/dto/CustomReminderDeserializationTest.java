package com.inventory.product.rest.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.inventory.product.rest.dto.request.BulkCreateInventoryRequest;
import com.inventory.product.rest.dto.request.CreateInventoryItemRequest;
import com.inventory.reminders.rest.dto.request.CustomReminderRequest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CustomReminderDeserializationTest {

  private ObjectMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
  }

  @Test
  void bulkPayload_deserializesNestedCustomReminderInstants() throws Exception {
    String json =
        """
        {
          "vendorId":"69467ba0fc700d2ed6d7d6b9",
          "items":[{
            "name":"Apple 3",
            "customReminders":[{
              "reminderAt":"2026-06-28T16:40:00.000Z",
              "endDate":"2026-06-28T17:40:00.000Z",
              "notes":"This is it"
            }]
          }]
        }
        """;

    BulkCreateInventoryRequest bulk = mapper.readValue(json, BulkCreateInventoryRequest.class);
    CreateInventoryItemRequest item = bulk.getItems().getFirst();
    assertNotNull(item.getCustomReminders());
    CustomReminderRequest reminder = item.getCustomReminders().getFirst();
    assertEquals(Instant.parse("2026-06-28T16:40:00.000Z"), reminder.getReminderAt());
    assertEquals(Instant.parse("2026-06-28T17:40:00.000Z"), reminder.getEndDate());
    assertEquals("This is it", reminder.getNotes());
  }
}
