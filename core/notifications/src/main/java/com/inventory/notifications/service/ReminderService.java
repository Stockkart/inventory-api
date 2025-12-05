package com.inventory.notifications.service;

import com.inventory.notifications.domain.model.Reminder;
import com.inventory.notifications.domain.model.ReminderStatus;
import com.inventory.notifications.domain.repository.ReminderRepository;
import com.inventory.notifications.rest.dto.*;
import com.inventory.notifications.rest.mapper.ReminderMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
@AllArgsConstructor
@Transactional
public class ReminderService {

    private final ReminderRepository reminderRepository;
    private final ReminderMapper reminderMapper;

    private static final long REMINDER_DAYS_BEFORE = 15L;

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
            Instant newReminderAt, // For custom reminder start
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

            // ---------- 1) NORMAL EXPIRY REMINDER ----------
            if (expiryDate != null) {
                Instant expiryEndDate = expiryDate;

                Instant expiryReminderAt = computeReminderTime(
                        reminderAt,          // explicit reminderAt
                        expiryDate,          // base = expiryDate
                        now,
                        "EXPIRY",
                        inventoryId
                );

                createAndSaveReminderIfValid(
                        shopId,
                        inventoryId,
                        expiryReminderAt,
                        expiryEndDate,
                        null,                // no notes for normal reminder
                        "EXPIRY"
                );
            }

            // ---------- 2) CUSTOM REMINDER ----------
            if (reminderEndDate != null) {
                Instant customEndDate = reminderEndDate;

                Instant customReminderAt = computeReminderTime(
                        newReminderAt,       // explicit custom start
                        customEndDate,       // base = reminderEndDate
                        now,
                        "CUSTOM",
                        inventoryId
                );

                createAndSaveReminderIfValid(
                        shopId,
                        inventoryId,
                        customReminderAt,
                        customEndDate,
                        notes,               // notes optional
                        "CUSTOM"
                );
            }

        } catch (Exception e) {
            log.error("Failed to create reminder(s) for inventory lot {} - {}", inventoryId, e.getMessage(), e);
            // swallow error so inventory creation doesn't fail
        }
    }

    // -------- common helper: compute reminderAt with default 15 days before --------
    private Instant computeReminderTime(
            Instant explicitReminderAt,
            Instant baseDate,        // expiryDate or reminderEndDate
            Instant now,
            String type,
            String inventoryId
    ) {
        if (baseDate == null && explicitReminderAt == null) {
            log.debug("No baseDate or explicitReminderAt for {} reminder on inventoryId={}, skipping", type, inventoryId);
            return null;
        }

        Instant result = explicitReminderAt;
        if (result == null && baseDate != null) {
            // default: 15 days before base date
            result = baseDate.minus(Duration.ofDays(REMINDER_DAYS_BEFORE));
        }

        if (result != null && result.isAfter(now)) {
            return result;
        }

        log.warn("Computed {} reminderAt {} is null or in the past for inventoryId={}, skipping", type, result, inventoryId);
        return null;
    }

    // -------- common helper: actually create + save reminder if valid --------
    private void createAndSaveReminderIfValid(
            String shopId,
            String inventoryId,
            Instant reminderAt,
            Instant endDate,
            String notes,
            String type
    ) {
        if (reminderAt == null || endDate == null) {
            log.debug("Not creating {} reminder for inventoryId={} because reminderAt or endDate is null",
                    type, inventoryId);
            return;
        }

        Reminder reminder = reminderMapper.toReminder(
                shopId,
                inventoryId,
                reminderAt,
                endDate,
                notes
        );

        reminderRepository.save(reminder);

        log.info("Created {} reminder for inventoryId={} with reminderAt={} and endDate={}, notes={}",
                type, inventoryId, reminderAt, endDate, notes);
    }

    public ReminderResponse snooze(String id, SnoozeReminderRequest request) {
        Reminder reminder = reminderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Reminder not found"));

        if (request.getSnoozeDays() == null || request.getSnoozeDays() <= 0) {
            throw new IllegalArgumentException("snoozeDays must be a positive number");
        }

        int daysToSnooze = request.getSnoozeDays();

        // 1) shift reminderAt
        if (reminder.getReminderAt() != null) {
            reminder.setReminderAt(
                    reminder.getReminderAt().plus(Duration.ofDays(daysToSnooze))
            );
        }

        // 2) increment snoozeDays
        reminder.setSnoozeDays(
                (reminder.getSnoozeDays() == null ? 0 : daysToSnooze)
        );

        // 3) update status
        reminder.setStatus(ReminderStatus.SNOOZED);

        // 4) update timestamp
        reminder.setUpdatedAt(Instant.now());

        reminderRepository.save(reminder);
        return reminderMapper.toResponse(reminder);
    }


    // CREATE (manual, no inventory auto-logic)
    public ReminderResponse create(CreateReminderRequest request) {
        // Basic sanity check
        if (request.getShopId() == null || request.getInventoryId() == null) {
            throw new IllegalArgumentException("shopId and inventoryId are required");
        }
        if (request.getReminderAt() == null) {
            throw new IllegalArgumentException("reminderAt is required");
        }

        Reminder reminder = reminderMapper.toReminder(
                request.getShopId(),
                request.getInventoryId(),
                request.getReminderAt(),
                request.getEndDate(),
                request.getNotes()
        );

        reminderRepository.save(reminder);
        return reminderMapper.toResponse(reminder);
    }

    // READ (by id)
    public ReminderResponse get(String id) {
        Reminder reminder = reminderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Reminder not found"));
        return reminderMapper.toResponse(reminder);
    }

    // UPDATE (manual)
    public ReminderResponse update(String id, UpdateReminderRequest request) {
        Reminder reminder = reminderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Reminder not found"));

        // Update fields only if provided
        if (request.getReminderAt() != null) {
            reminder.setReminderAt(request.getReminderAt());
        }
        if (request.getEndDate() != null) {
            reminder.setEndDate(request.getEndDate());
        }
        if (request.getNotes() != null) {
            reminder.setNotes(request.getNotes());
        }
        if (request.getStatus() != null) {
            try {
                reminder.setStatus(ReminderStatus.valueOf(request.getStatus().toUpperCase()));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Invalid status value: " + request.getStatus());
            }
        }

        reminder.setUpdatedAt(Instant.now());

        reminderRepository.save(reminder);
        return reminderMapper.toResponse(reminder);
    }

    // DELETE
    public void delete(String id) {
        if (!reminderRepository.existsById(id)) {
            throw new IllegalArgumentException("Reminder not found");
        }
        reminderRepository.deleteById(id);
    }

}
