package com.inventory.reminders.api;

/**
 * Lot counts by expiry for a shop.
 *
 * @param expiringSoonDays the window "soon" was counted with
 */
public record ExpiryBuckets(
    int expired,
    int expiringWithin7Days,
    int expiringWithinSoonDays,
    int expiringSoonTotal,
    int expiringSoonDays) {}
