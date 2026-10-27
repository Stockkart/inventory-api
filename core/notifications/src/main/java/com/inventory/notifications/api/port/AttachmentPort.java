package com.inventory.notifications.api.port;

import com.inventory.notifications.api.Attachment;
import com.inventory.notifications.api.AttachmentRef;
import java.util.Optional;

/**
 * Port (L2) for documents rendered when an outbox message is sent rather than when it is queued
 * (HR decision 1). Implemented by higher modules, e.g. HR's payslip resolver; the queue processor
 * picks the implementation whose {@link #supports} answers for the message's {@link
 * AttachmentRef#kind() kind}.
 */
public interface AttachmentPort {

  /** True when this port renders documents of {@code kind}. */
  boolean supports(String kind);

  /**
   * Renders the referenced document.
   *
   * @return the file, or empty when the document cannot be produced right now (for example the
   *     payroll run was reopened); the message is then retried with the usual backoff
   */
  Optional<Attachment> resolve(AttachmentRef ref);
}
