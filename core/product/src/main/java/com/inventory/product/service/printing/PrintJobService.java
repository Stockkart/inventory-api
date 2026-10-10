package com.inventory.product.service.printing;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.documentservice.domain.DotMatrixDocument;
import com.inventory.product.config.PrintBridgeProperties;
import com.inventory.product.domain.model.PrintJob;
import com.inventory.product.domain.model.enums.PrintAction;
import com.inventory.product.domain.model.enums.PrintActionReason;
import com.inventory.product.domain.model.enums.PrintBridgeState;
import com.inventory.product.domain.model.enums.PrintDocumentSource;
import com.inventory.product.domain.model.enums.PrintJobStatus;
import com.inventory.product.domain.model.enums.PrintOutcome;
import com.inventory.product.domain.repository.PrintJobRepository;
import com.inventory.product.rest.dto.request.CreatePrintJobRequest;
import com.inventory.product.rest.dto.request.ReportPrintOutcomeRequest;
import com.inventory.product.rest.dto.response.PrintBridgeJobPayload;
import com.inventory.product.rest.dto.response.PrintDownload;
import com.inventory.product.rest.dto.response.PrintJobResponse;
import com.inventory.product.rest.dto.response.PrintOutcomeResponse;
import com.inventory.product.rest.dto.response.PrintPollPolicy;
import com.inventory.product.service.CreditNoteService;
import com.inventory.product.service.InvoiceService;
import com.inventory.product.validation.PrintJobValidator;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Every decision about printing a document on a dot-matrix printer: whether it goes to the bridge
 * or downloads, what the bridge is told, and what a reported result means. The browser carries
 * the answers between this service and the bridge; it decides none of them.
 *
 * <p>Printing is at-least-once. A job is recorded before the browser sends it, and the record says
 * only what was observed: a job nobody reported on becomes EXPIRED (outcome unknown), never
 * "did not print".
 */
@Service
@Slf4j
public class PrintJobService {

  /** 0 tells the bridge to use its own configured copies, e.g. original plus customer copy. */
  private static final int BRIDGE_DEFAULT_COPIES = 0;

  private final PrintJobRepository printJobRepository;
  private final PrintBridgeService printBridgeService;
  private final PrintBridgeProperties properties;
  private final PrintJobValidator printJobValidator;
  private final InvoiceService invoiceService;
  private final CreditNoteService creditNoteService;
  private final Clock clock;

  @Autowired
  public PrintJobService(
      PrintJobRepository printJobRepository,
      PrintBridgeService printBridgeService,
      PrintBridgeProperties properties,
      PrintJobValidator printJobValidator,
      InvoiceService invoiceService,
      CreditNoteService creditNoteService) {
    this(
        printJobRepository,
        printBridgeService,
        properties,
        printJobValidator,
        invoiceService,
        creditNoteService,
        Clock.systemUTC());
  }

  /** Test-only: inject a fixed clock for in-flight expiry coverage. */
  PrintJobService(
      PrintJobRepository printJobRepository,
      PrintBridgeService printBridgeService,
      PrintBridgeProperties properties,
      PrintJobValidator printJobValidator,
      InvoiceService invoiceService,
      CreditNoteService creditNoteService,
      Clock clock) {
    this.printJobRepository = printJobRepository;
    this.printBridgeService = printBridgeService;
    this.properties = properties;
    this.printJobValidator = printJobValidator;
    this.invoiceService = invoiceService;
    this.creditNoteService = creditNoteService;
    this.clock = clock;
  }

  /**
   * Record a print job and say how to carry it out.
   *
   * <p>If the same document already has a job in flight, that job is returned as IN_PROGRESS and
   * nothing is sent: a second click must not put a second copy of a GST invoice on the printer.
   * A job left in flight past {@code print-bridge.in-flight-timeout} is marked EXPIRED first, so
   * a closed browser cannot stop the document ever printing again.
   */
  public PrintJobResponse create(String shopId, String userId, CreatePrintJobRequest request) {
    printJobValidator.validateCreateRequest(request);
    // Rendering first also proves the document exists and belongs to this shop.
    DotMatrixDocument document = render(request.getSource(), request.getDocumentId(), shopId);
    PrintBridgeState bridgeState = printBridgeService.stateFor(request.getBridge());

    Optional<PrintJob> inFlight = findInFlight(shopId, request);
    if (inFlight.isPresent()) {
      return inProgress(inFlight.get());
    }

    PrintJob job = newJob(shopId, userId, request, document, bridgeState);
    try {
      job = printJobRepository.save(job);
    } catch (DuplicateKeyException e) {
      // Another click created the in-flight job between our read and this write.
      return findInFlight(shopId, request).map(this::inProgress).orElseThrow(() -> e);
    }
    log.info(
        "Print job {} for {} {} (shop {}): {} via {}",
        job.getId(), request.getSource(), request.getDocumentId(), shopId, job.getAction(),
        bridgeState);
    return toResponse(job, document);
  }

  /**
   * Record what the browser saw when it sent the job, and say what that means. Reporting on a job
   * that has already settled changes nothing and returns its standing outcome.
   */
  public PrintOutcomeResponse reportOutcome(
      String shopId, String printJobId, ReportPrintOutcomeRequest request) {
    printJobValidator.validateOutcomeRequest(request);
    PrintJob job =
        printJobRepository
            .findByIdAndShopId(printJobId, shopId)
            .orElseThrow(() -> new ResourceNotFoundException("PrintJob", "id", printJobId));
    if (!job.getStatus().isInFlight()) {
      return outcomeOf(job);
    }

    switch (request.getObservation()) {
      case PRINTED -> settle(job, PrintJobStatus.PRINTED);
      case FAILED -> settle(job, PrintJobStatus.FAILED_PRINTER);
      case STILL_QUEUED -> job.setStatus(PrintJobStatus.SUBMITTED);
      case UNREACHABLE -> settle(job, PrintJobStatus.FAILED_CLIENT);
      case DUPLICATE -> settle(job, PrintJobStatus.DUPLICATE_SUPPRESSED);
      case REJECTED -> settle(job, PrintJobStatus.FAILED_BRIDGE);
    }
    if (StringUtils.hasText(request.getBridgeJobId())) {
      job.setBridgeJobId(request.getBridgeJobId());
    }
    if (StringUtils.hasText(request.getError())) {
      job.setError(request.getError());
    }
    printJobRepository.save(job);
    return outcomeOf(job);
  }

  private DotMatrixDocument render(PrintDocumentSource source, String documentId, String shopId) {
    return switch (source) {
      case SALE -> invoiceService.renderInvoiceForPrint(documentId, shopId);
      case REFUND -> creditNoteService.renderCustomerCreditNoteForPrint(documentId, shopId);
      case VENDOR_RETURN -> creditNoteService.renderVendorCreditNoteForPrint(documentId, shopId);
    };
  }

  private Optional<PrintJob> findInFlight(String shopId, CreatePrintJobRequest request) {
    Optional<PrintJob> found =
        printJobRepository.findFirstByShopIdAndSourceAndDocumentIdAndStatusIn(
            shopId, request.getSource(), request.getDocumentId(), PrintJobStatus.IN_FLIGHT);
    if (found.isEmpty()) {
      return found;
    }
    PrintJob job = found.get();
    Instant staleBefore = clock.instant().minus(properties.getInFlightTimeout());
    if (job.getCreatedAt() != null && job.getCreatedAt().isBefore(staleBefore)) {
      settle(job, PrintJobStatus.EXPIRED);
      printJobRepository.save(job);
      return Optional.empty();
    }
    return found;
  }

  private PrintJob newJob(
      String shopId,
      String userId,
      CreatePrintJobRequest request,
      DotMatrixDocument document,
      PrintBridgeState bridgeState) {
    boolean noBridge = bridgeState == PrintBridgeState.NOT_DETECTED;
    PrintJob job = new PrintJob();
    job.setShopId(shopId);
    job.setSource(request.getSource());
    job.setDocumentId(request.getDocumentId());
    job.setDocumentKind(document.kind());
    job.setDocumentNumber(document.documentNumber());
    job.setBridgeState(bridgeState);
    job.setBridgeVersion(noBridge ? null : request.getBridge().getVersion());
    job.setSelectedPrinter(noBridge ? null : request.getBridge().getSelectedPrinter());
    job.setAction(noBridge ? PrintAction.DOWNLOAD : PrintAction.BRIDGE);
    job.setReason(noBridge ? PrintActionReason.BRIDGE_UNAVAILABLE : null);
    job.setCreatedAt(clock.instant());
    job.setCreatedByUserId(userId);
    if (noBridge) {
      settle(job, PrintJobStatus.DOWNLOAD_READY);
    } else {
      job.setStatus(PrintJobStatus.PENDING);
    }
    return job;
  }

  private void settle(PrintJob job, PrintJobStatus status) {
    job.setStatus(status);
    job.setCompletedAt(clock.instant());
  }

  private PrintJobResponse toResponse(PrintJob job, DotMatrixDocument document) {
    PrintBridgeJobPayload payload =
        job.getAction() == PrintAction.BRIDGE
            ? new PrintBridgeJobPayload(
                document.kind().name(), job.getDocumentId(), BRIDGE_DEFAULT_COPIES, document.text())
            : null;
    return new PrintJobResponse(
        job.getId(),
        job.getAction(),
        job.getReason(),
        job.getBridgeState(),
        document.kind(),
        payload,
        new PrintDownload(filenameFor(job), document.text()),
        new PrintPollPolicy(properties.getPollIntervalMs(), properties.getPollBudgetMs()));
  }

  private PrintJobResponse inProgress(PrintJob existing) {
    return new PrintJobResponse(
        existing.getId(),
        PrintAction.IN_PROGRESS,
        PrintActionReason.PRINT_IN_PROGRESS,
        existing.getBridgeState(),
        existing.getDocumentKind(),
        null,
        null,
        null);
  }

  /** e.g. {@code invoice-T001148.prn}, {@code estimate-EST-00022.prn}, {@code credit-note-CN-7.prn}. */
  static String filenameFor(PrintJob job) {
    String kind = job.getDocumentKind().name().toLowerCase(Locale.ROOT).replace('_', '-');
    String number =
        StringUtils.hasText(job.getDocumentNumber()) ? job.getDocumentNumber() : job.getDocumentId();
    return kind + "-" + number.replaceAll("[^A-Za-z0-9_-]", "-") + ".prn";
  }

  private static PrintOutcomeResponse outcomeOf(PrintJob job) {
    return switch (job.getStatus()) {
      case PRINTED -> outcome(job, PrintOutcome.PRINTED, true, false, false);
      // A printer fault stays on screen: the operator needs to read it to reload paper and retry.
      case FAILED_PRINTER -> outcome(job, PrintOutcome.FAILED_PRINTER, false, true, false);
      // Still printing: closing is right, retrying is not - it is about to come out.
      case SUBMITTED, PENDING, EXPIRED -> outcome(job, PrintOutcome.STILL_QUEUED, true, false, false);
      // Nothing reached the printer, so the file is an honest fallback, not a second copy.
      case FAILED_CLIENT -> outcome(job, PrintOutcome.BRIDGE_UNREACHABLE, true, false, true);
      case DOWNLOAD_READY -> outcome(job, PrintOutcome.BRIDGE_UNREACHABLE, true, false, true);
      case DUPLICATE_SUPPRESSED -> outcome(job, PrintOutcome.ALREADY_SENT, true, false, false);
      // The bridge answered; it may have printed. No automatic fallback, which could print twice.
      case FAILED_BRIDGE -> outcome(job, PrintOutcome.BRIDGE_REJECTED, false, true, false);
    };
  }

  private static PrintOutcomeResponse outcome(
      PrintJob job, PrintOutcome outcome, boolean shouldClose, boolean retryable, boolean download) {
    return new PrintOutcomeResponse(
        outcome, job.getStatus(), shouldClose, retryable, download, job.getError());
  }
}
