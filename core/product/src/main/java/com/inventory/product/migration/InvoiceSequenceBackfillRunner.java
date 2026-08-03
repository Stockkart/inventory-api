package com.inventory.product.migration;

import com.inventory.product.service.InvoiceSeriesService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Idempotent startup backfill for legacy {@code invoice_sequences} REGULAR docs without {@code
 * fyLabel}. Enabled by default; safe to re-run.
 */
@Component
@Slf4j
public class InvoiceSequenceBackfillRunner {

  private final InvoiceSeriesService invoiceSeriesService;

  @Value("${stockkart.invoice-sequence-backfill.enabled:true}")
  private boolean enabled;

  public InvoiceSequenceBackfillRunner(InvoiceSeriesService invoiceSeriesService) {
    this.invoiceSeriesService = invoiceSeriesService;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Order(30)
  public void run() {
    if (!enabled) {
      return;
    }
    try {
      int updated = invoiceSeriesService.backfillExistingShops();
      if (updated > 0) {
        log.info("Invoice sequence backfill updated {} shop(s)", updated);
      }
    } catch (Exception ex) {
      log.warn("Invoice sequence backfill failed: {}", ex.getMessage(), ex);
    }
  }
}
