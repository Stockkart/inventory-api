package com.inventory.product.service.printing;

import com.inventory.documentservice.domain.DotMatrixDocument;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.product.config.PrintBridgeProperties;
import com.inventory.product.domain.model.enums.PrintAction;
import com.inventory.product.domain.model.enums.PrintActionReason;
import com.inventory.product.domain.model.enums.PrintBridgeState;
import com.inventory.product.domain.model.enums.PrintDocumentSource;
import com.inventory.product.domain.model.enums.PrintOutcome;
import com.inventory.product.rest.dto.request.CreatePrintJobRequest;
import com.inventory.product.rest.dto.request.ReportPrintOutcomeRequest;
import com.inventory.product.rest.dto.response.PrintBridgeJobPayload;
import com.inventory.product.rest.dto.response.PrintDownload;
import com.inventory.product.rest.dto.response.PrintJobResponse;
import com.inventory.product.rest.dto.response.PrintOutcomeResponse;
import com.inventory.product.rest.dto.response.PrintPollPolicy;
import com.inventory.product.service.CreditNoteService;
import com.inventory.product.service.InvoiceService;
import com.inventory.product.utils.constants.ProductMetricsConstants;
import com.inventory.product.validation.PrintJobValidator;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Every decision about printing a document on a dot-matrix printer: whether it goes to the bridge
 * or downloads, what the bridge is told, and what a reported result means. The browser carries
 * the answers between this service and the bridge; it decides none of them.
 *
 * <p>Nothing is stored. The bridge holds the job itself, and the browser keeps a short-lived
 * record of what it sent (for {@code print-bridge.in-flight-timeout}) and reports it here as
 * {@code pendingAgeMs}, so a second click is judged here without a table behind it.
 *
 * <p>Printing is at-least-once: a job nobody saw finish is UNCONFIRMED (outcome unknown), never
 * "did not print".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrintJobService {

  /** 0 tells the bridge to use its own configured copies, e.g. original plus customer copy. */
  private static final int BRIDGE_DEFAULT_COPIES = 0;

  private final PrintBridgeService printBridgeService;
  private final PrintBridgeProperties properties;
  private final PrintJobValidator printJobValidator;
  private final InvoiceService invoiceService;
  private final CreditNoteService creditNoteService;
  private final MetricsWrapper metrics;

  /**
   * Say how to print one document.
   *
   * <p>If this browser sent the same document within the in-flight time and has not seen it
   * finish, the answer is IN_PROGRESS and nothing is sent: a second click must not put a second
   * copy of a GST invoice on the printer.
   */
  public PrintJobResponse create(String shopId, CreatePrintJobRequest request) {
    printJobValidator.validateCreateRequest(request);
    // Rendering first also proves the document exists and belongs to this shop.
    DotMatrixDocument document = render(request.getSource(), request.getDocumentId(), shopId);
    PrintBridgeState bridgeState = printBridgeService.stateFor(request.getBridge());

    if (isInFlight(request.getPendingAgeMs())) {
      recordJob(request.getSource(), PrintAction.IN_PROGRESS);
      return inProgress(document, bridgeState);
    }

    boolean noBridge = bridgeState == PrintBridgeState.NOT_DETECTED;
    PrintAction action = noBridge ? PrintAction.DOWNLOAD : PrintAction.BRIDGE;
    recordJob(request.getSource(), action);
    log.info(
        "Print {} {} {} (shop {}): {} via {}",
        document.kind(), document.documentNumber(), request.getDocumentId(), shopId, action,
        bridgeState);
    return new PrintJobResponse(
        action,
        noBridge ? PrintActionReason.BRIDGE_UNAVAILABLE : null,
        bridgeState,
        document.kind(),
        document.documentNumber(),
        noBridge
            ? null
            : new PrintBridgeJobPayload(
                document.kind().name(),
                request.getDocumentId(),
                BRIDGE_DEFAULT_COPIES,
                document.text()),
        new PrintDownload(filenameFor(document, request.getDocumentId()), document.text()),
        new PrintPollPolicy(properties.getPollIntervalMs(), properties.getPollBudgetMs()),
        inFlightTtlMs());
  }

  /** Say what the browser's observation of a sent job means, and what the print screen does. */
  public PrintOutcomeResponse reportOutcome(String shopId, ReportPrintOutcomeRequest request) {
    printJobValidator.validateOutcomeRequest(request);
    PrintOutcomeResponse response = outcomeFor(request);
    metrics.record(
        ProductMetricsConstants.PRINT_JOB_OUTCOMES,
        1,
        "module",
        ProductMetricsConstants.MODULE,
        "operation",
        tag(request.getSource()),
        "outcome",
        tag(response.getOutcome()));
    log.info(
        "Print outcome {} for {} (shop {}, bridge job {}): {}",
        request.getObservation(), request.getSource(), shopId, request.getBridgeJobId(),
        response.getOutcome());
    return response;
  }

  private DotMatrixDocument render(PrintDocumentSource source, String documentId, String shopId) {
    return switch (source) {
      case SALE -> invoiceService.renderInvoiceForPrint(documentId, shopId);
      case REFUND -> creditNoteService.renderCustomerCreditNoteForPrint(documentId, shopId);
      case VENDOR_RETURN -> creditNoteService.renderVendorCreditNoteForPrint(documentId, shopId);
    };
  }

  private boolean isInFlight(Long pendingAgeMs) {
    return pendingAgeMs != null && pendingAgeMs < inFlightTtlMs();
  }

  private long inFlightTtlMs() {
    return properties.getInFlightTimeout().toMillis();
  }

  private PrintJobResponse inProgress(DotMatrixDocument document, PrintBridgeState bridgeState) {
    return new PrintJobResponse(
        PrintAction.IN_PROGRESS,
        PrintActionReason.PRINT_IN_PROGRESS,
        bridgeState,
        document.kind(),
        document.documentNumber(),
        null,
        null,
        null,
        inFlightTtlMs());
  }

  /** One per print click: what the backend told the browser to do with it. */
  private void recordJob(PrintDocumentSource source, PrintAction action) {
    metrics.record(
        ProductMetricsConstants.PRINT_JOBS,
        1,
        "module",
        ProductMetricsConstants.MODULE,
        "operation",
        tag(source),
        "outcome",
        tag(action));
  }

  private static String tag(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }

  /** e.g. {@code invoice-T001148.prn}, {@code estimate-EST-00022.prn}, {@code credit-note-CN-7.prn}. */
  static String filenameFor(DotMatrixDocument document, String documentId) {
    String kind = document.kind().name().toLowerCase(Locale.ROOT).replace('_', '-');
    String number =
        StringUtils.hasText(document.documentNumber()) ? document.documentNumber() : documentId;
    return kind + "-" + number.replaceAll("[^A-Za-z0-9_-]", "-") + ".prn";
  }

  private static PrintOutcomeResponse outcomeFor(ReportPrintOutcomeRequest request) {
    String error = StringUtils.hasText(request.getError()) ? request.getError() : null;
    return switch (request.getObservation()) {
      case PRINTED -> outcome(PrintOutcome.PRINTED, true, false, false, null);
      // A printer fault stays on screen: the operator needs to read it to reload paper and retry.
      case FAILED -> outcome(PrintOutcome.FAILED_PRINTER, false, true, false, error);
      // Still printing: closing is right, retrying is not - it is about to come out.
      case STILL_QUEUED -> outcome(PrintOutcome.STILL_QUEUED, true, false, false, null);
      // Nothing reached the printer, so the file is an honest fallback, not a second copy.
      case UNREACHABLE -> outcome(PrintOutcome.BRIDGE_UNREACHABLE, true, false, true, null);
      case DUPLICATE -> outcome(PrintOutcome.ALREADY_SENT, true, false, false, null);
      // The bridge answered; it may have printed. No automatic fallback, which could print twice.
      case REJECTED -> outcome(PrintOutcome.BRIDGE_REJECTED, false, true, false, error);
      // Nobody saw it finish. It may have printed, so no file and no retry prompt.
      case EXPIRED -> outcome(PrintOutcome.UNCONFIRMED, true, false, false, null);
    };
  }

  private static PrintOutcomeResponse outcome(
      PrintOutcome outcome,
      boolean shouldClose,
      boolean retryable,
      boolean download,
      String error) {
    return new PrintOutcomeResponse(outcome, shouldClose, retryable, download, error);
  }
}
