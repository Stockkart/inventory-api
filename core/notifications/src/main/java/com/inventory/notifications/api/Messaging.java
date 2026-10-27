package com.inventory.notifications.api;

import java.util.List;
import java.util.Map;

/**
 * Sends email through the outbox. Messages are stored and delivered by the queue processor, so
 * a slow or failing provider never blocks the caller.
 */
public interface Messaging {

  /**
   * Queues an email built from a template. Returns at once; the message is stored on another
   * thread. An empty recipient is skipped.
   *
   * @param variables template variables (e.g. userName, userEmail)
   */
  void sendEmailWithTemplate(String to, EmailTemplate template, Map<String, Object> variables);

  /** Queues a plain email (subject and HTML body). Returns at once. */
  void sendEmail(String to, String subject, String htmlBody, Map<String, Object> metadata);

  /**
   * Queues template emails whose attachment is rendered at send time (for example payslips), in
   * one insert, before returning. Entries without a recipient are skipped.
   *
   * @return messages queued
   */
  int enqueueTemplateEmails(EmailTemplate template, List<TemplateEmail> emails);
}
