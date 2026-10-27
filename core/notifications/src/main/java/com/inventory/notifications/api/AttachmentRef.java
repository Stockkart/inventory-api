package com.inventory.notifications.api;

/**
 * Points at a document another module renders when the email is sent, so no file bytes are
 * stored with the message.
 *
 * @param kind which {@link com.inventory.notifications.api.port.AttachmentPort} renders it, e.g.
 *     {@code PAYSLIP}
 */
public record AttachmentRef(String kind, String runId, String lineId, String shopId) {}
