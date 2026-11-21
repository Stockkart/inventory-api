package com.inventory.hr.adapter;

import com.inventory.notifications.api.port.AttachmentPort;
import com.inventory.notifications.api.AttachmentRef;
import com.inventory.notifications.api.Attachment;
import com.inventory.hr.service.PayslipService;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Renders emailed payslips when the outbox sends them (decision 1), so a 300-line "Email
 * payslips" call stays under a second and no PDF bytes are stored.
 */
@Slf4j
@Component
public class PayslipAttachmentResolver implements AttachmentPort {

  @Autowired private PayslipService payslipService;

  @Override
  public boolean supports(String kind) {
    return PayslipService.ATTACHMENT_KIND.equals(kind);
  }

  @Override
  public Optional<Attachment> resolve(AttachmentRef ref) {
    try {
      return Optional.ofNullable(payslipService.renderAttachment(ref));
    } catch (RuntimeException e) {
      // ids and the exception class only: the outbox retries with its backoff
      log.warn("[hr-payslip] could not render payslip of line {}: {}",
          ref != null ? ref.lineId() : null, e.getClass().getSimpleName());
      return Optional.empty();
    }
  }
}
