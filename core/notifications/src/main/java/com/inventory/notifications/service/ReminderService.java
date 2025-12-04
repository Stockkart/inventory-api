package com.inventory.notifications.service;

import com.inventory.notifications.domain.model.Reminder;
import com.inventory.notifications.domain.repository.ReminderRepository;
import com.inventory.notifications.rest.dto.ReminderListResponse;
import com.inventory.notifications.rest.dto.ReminderResponse;
import com.inventory.notifications.rest.dto.SnoozeReminderRequest;
import com.inventory.notifications.rest.mapper.ReminderMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Service
@AllArgsConstructor
@Transactional
public class ReminderService {

    @Autowired
    private final ReminderRepository reminderRepository;

    @Autowired
    private final ReminderMapper reminderMapper;

    private static final long REMINDER_DAYS_BEFORE_EXPIRY = 7; // Remind 7 days before expiry

    public ReminderListResponse list(String shopId) {
        return reminderMapper.toReminderListResponse(reminderRepository.findByShopId(shopId));
    }

    public boolean createReminder(String shopId, String inventoryId, Instant reminderAt, Instant expiryDate) {
        Reminder reminder = reminderMapper.toReminder(shopId, inventoryId, reminderAt, expiryDate, "");
        reminderRepository.save(reminder);
        return true;
    }

    /**
     * Create a reminder when a new inventory lot is created.
     * All defaulting & validation is handled here.
     *
     * @param shopId            shop that owns the inventory
     * @param inventoryId       inventory id
     * @param expiryDate        expiry date from inventory (can be null)
     * @param reminderAt        reminderAt from CreateInventoryRequest (can be null)
     * @param reminderEndDate    reminderEndDate from CreateInventoryRequest (can be null)
     * @param notes             reminderNotes from CreateInventoryRequest (can be null)
     */
    @Async
    public void createReminderForInventoryCreate(
            String shopId,
            String inventoryId,
            Instant expiryDate,
            Instant reminderAt,    // For standard expiry reminder
            Instant requestNewReminderAt, // For custom reminder start
            Instant reminderEndDate,       // For custom reminder end
            String notes                  // For custom reminder notes
    ) {
        // If neither normal nor custom have the required key dates, skip completely
        if (expiryDate == null && reminderEndDate == null) {
            log.debug("No valid expiryDate or reminderEndDate for inventoryId={}, skipping reminder creation", inventoryId);
            return;
        }

        try {
            Instant now = Instant.now();
            // 15 days before (for both normal and custom)
            final long REMINDER_DAYS_BEFORE = 15L;

            // ---------- 1) NORMAL EXPIRY REMINDER ----------
            // Rule: only when expiryDate is present
            if (expiryDate != null) {
                // endDate for expiry reminder = expiryDate
                Instant expiryEndDate = expiryDate;

                // Decide reminderAt for expiry reminder
                Instant expiryReminderAt = reminderAt;
                if (expiryReminderAt == null) {
                    // default: 15 days before expiry
                    expiryReminderAt = expiryDate.minus(Duration.ofDays(REMINDER_DAYS_BEFORE));
                }

                if (expiryReminderAt != null && expiryReminderAt.isAfter(now)) {
                    Reminder expiryReminder = reminderMapper.toReminder(
                            shopId,
                            inventoryId,
                            expiryReminderAt,
                            expiryEndDate,
                            null   // no notes for the normal expiry reminder
                    );

                    reminderRepository.save(expiryReminder);

                    log.info("Created EXPIRY reminder for inventoryId={} with reminderAt={} and endDate={}",
                            inventoryId, expiryReminderAt, expiryEndDate);
                } else {
                    log.warn("Skipping EXPIRY reminder for inventoryId={} because reminderAt {} is null or in the past",
                            inventoryId, expiryReminderAt);
                }
            }

            // ---------- 2) CUSTOM REMINDER ----------
            // Rule: only if reminderEndDate is present
            if (reminderEndDate != null) {

                Instant customEndDate = reminderEndDate;

                // Decide reminderAt for custom reminder
                Instant customReminderAt = requestNewReminderAt;
                if (customReminderAt == null) {
                    // default: 15 days before custom end date
                    customReminderAt = customEndDate.minus(Duration.ofDays(REMINDER_DAYS_BEFORE));
                }

                if (customReminderAt != null && customReminderAt.isAfter(now)) {
                    Reminder customReminder = reminderMapper.toReminder(
                            shopId,
                            inventoryId,
                            customReminderAt,
                            customEndDate,
                            notes  // notes are optional, can be null
                    );

                    reminderRepository.save(customReminder);

                    log.info("Created CUSTOM reminder for inventoryId={} with reminderAt={} and endDate={}, notes={}",
                            inventoryId, customReminderAt, customEndDate, notes);
                } else {
                    log.warn("Skipping CUSTOM reminder for inventoryId={} because reminderAt {} is null or in the past",
                            inventoryId, customReminderAt);
                }
            }

        } catch (Exception e) {
            log.error("Failed to create reminder(s) for inventory lot {} - {}", inventoryId, e.getMessage(), e);
            // swallow error so inventory creation doesn't fail
        }
    }



    @Async
    public void createReminderForExpiry(String shopId, String inventoryId, Instant expiryDate) {
        createReminderForInventoryCreate(shopId, inventoryId, expiryDate, null, null, null, null);
    }


    public ReminderResponse snooze(String id, SnoozeReminderRequest request) {
        Reminder reminder = reminderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Reminder not found"));

        Reminder updatedReminder = reminderMapper.updateReminder(reminder, id, request);
        reminderRepository.save(updatedReminder);

        return reminderMapper.toResponse(updatedReminder);
    }
}