package com.inventory.notifications.api;

import java.util.Map;

/**
 * One recipient of {@link Messaging#enqueueTemplateEmails}.
 *
 * @param attachmentRef document to render and attach when the email is sent; null for none
 */
public record TemplateEmail(String to, Map<String, Object> variables, AttachmentRef attachmentRef) {}
