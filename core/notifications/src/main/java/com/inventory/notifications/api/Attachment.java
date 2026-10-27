package com.inventory.notifications.api;

/** A rendered file attached to an email; {@code base64} holds the file's bytes. */
public record Attachment(String fileName, String contentType, String base64) {}
