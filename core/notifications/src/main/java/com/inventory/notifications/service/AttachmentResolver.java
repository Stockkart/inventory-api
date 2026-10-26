package com.inventory.notifications.service;

import com.inventory.notifications.domain.model.OutboundMessage;
import java.util.Optional;

/**
 * Port (L2) for documents rendered when an outbox message is sent rather than when it is queued
 * (HR decision 1). Implemented by higher modules, e.g. HR's payslip resolver; {@link
 * MessageQueueProcessor} picks the implementation whose {@link #supports} answers for the
 * message's {@link OutboundMessage.AttachmentRef#getKind() kind}.
 */
public interface AttachmentResolver {

  /** True when this resolver renders documents of {@code kind}. */
  boolean supports(String kind);

  /**
   * Renders the referenced document.
   *
   * @return the file, or empty when the document cannot be produced right now (for example the
   *     payroll run was reopened); the message is then retried with the usual backoff
   */
  Optional<OutboundMessage.Attachment> resolve(OutboundMessage.AttachmentRef ref);
}
