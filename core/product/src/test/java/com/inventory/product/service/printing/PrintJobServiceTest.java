package com.inventory.product.service.printing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.documentservice.domain.DotMatrixDocument;
import com.inventory.documentservice.domain.DotMatrixDocumentKind;
import com.inventory.product.config.PrintBridgeProperties;
import com.inventory.product.domain.model.PrintJob;
import com.inventory.product.domain.model.enums.PrintAction;
import com.inventory.product.domain.model.enums.PrintActionReason;
import com.inventory.product.domain.model.enums.PrintDocumentSource;
import com.inventory.product.domain.model.enums.PrintJobStatus;
import com.inventory.product.domain.model.enums.PrintObservation;
import com.inventory.product.domain.model.enums.PrintOutcome;
import com.inventory.product.domain.repository.PrintJobRepository;
import com.inventory.product.rest.dto.request.CreatePrintJobRequest;
import com.inventory.product.rest.dto.request.PrintBridgeObservation;
import com.inventory.product.rest.dto.request.ReportPrintOutcomeRequest;
import com.inventory.product.rest.dto.response.PrintJobResponse;
import com.inventory.product.rest.dto.response.PrintOutcomeResponse;
import com.inventory.product.service.CreditNoteService;
import com.inventory.product.service.InvoiceService;
import com.inventory.product.validation.PrintJobValidator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/** Every print decision is made here; the browser only carries the answers. */
class PrintJobServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");
  private static final String SHOP = "shop-1";

  private PrintJobRepository repository;
  private InvoiceService invoiceService;
  private CreditNoteService creditNoteService;
  private PrintJobService service;

  @BeforeEach
  void setUp() {
    repository = mock(PrintJobRepository.class);
    invoiceService = mock(InvoiceService.class);
    creditNoteService = mock(CreditNoteService.class);
    PrintBridgeProperties properties = new PrintBridgeProperties();
    properties.setMinimumVersion("0.11.0");
    properties.setInFlightTimeout(Duration.ofMinutes(2));
    PrintJobValidator validator = new PrintJobValidator();
    service =
        new PrintJobService(
            repository,
            new PrintBridgeService(properties, validator),
            properties,
            validator,
            invoiceService,
            creditNoteService,
            Clock.fixed(NOW, ZoneOffset.UTC));
    when(repository.save(any(PrintJob.class)))
        .thenAnswer(
            call -> {
              PrintJob job = call.getArgument(0);
              if (job.getId() == null) {
                job.setId("job-new");
              }
              return job;
            });
    when(repository.findFirstByShopIdAndSourceAndDocumentIdAndStatusIn(
            any(), any(), any(), any()))
        .thenReturn(Optional.empty());
  }

  @Test
  void aConnectedBridgeGetsTheTextWithTheKindTheRendererDecided() {
    when(invoiceService.renderInvoiceForPrint("p-1", SHOP))
        .thenReturn(new DotMatrixDocument("TEXT", DotMatrixDocumentKind.ESTIMATE, "EST/00022"));

    PrintJobResponse response = service.create(SHOP, "u-1", sale("p-1", true, "0.12.0"));

    assertEquals(PrintAction.BRIDGE, response.getAction());
    assertNull(response.getReason());
    assertEquals("ESTIMATE", response.getBridgeRequest().getDocType());
    assertEquals("p-1", response.getBridgeRequest().getDocId());
    assertEquals(0, response.getBridgeRequest().getCopies());
    assertEquals("TEXT", response.getBridgeRequest().getText());
    assertEquals("estimate-EST-00022.prn", response.getDownload().getFilename());
    assertEquals(5000, response.getPoll().getBudgetMs());
    assertEquals(PrintJobStatus.PENDING, saved().getStatus());
  }

  @Test
  void noBridgeDownloadsAndSaysWhy() {
    when(invoiceService.renderInvoiceForPrint("p-1", SHOP))
        .thenReturn(new DotMatrixDocument("TEXT", DotMatrixDocumentKind.INVOICE, "T001148"));

    PrintJobResponse response = service.create(SHOP, "u-1", sale("p-1", false, null));

    assertEquals(PrintAction.DOWNLOAD, response.getAction());
    assertEquals(PrintActionReason.BRIDGE_UNAVAILABLE, response.getReason());
    assertNull(response.getBridgeRequest());
    assertEquals("invoice-T001148.prn", response.getDownload().getFilename());
    assertEquals(PrintJobStatus.DOWNLOAD_READY, saved().getStatus());
  }

  @Test
  void aDocumentAlreadyInFlightIsNotSentAgain() {
    when(invoiceService.renderInvoiceForPrint("p-1", SHOP))
        .thenReturn(new DotMatrixDocument("TEXT", DotMatrixDocumentKind.INVOICE, "T001148"));
    PrintJob inFlight = job("job-old", PrintJobStatus.PENDING, NOW.minusSeconds(10));
    when(repository.findFirstByShopIdAndSourceAndDocumentIdAndStatusIn(
            eq(SHOP), eq(PrintDocumentSource.SALE), eq("p-1"), any()))
        .thenReturn(Optional.of(inFlight));

    PrintJobResponse response = service.create(SHOP, "u-1", sale("p-1", true, "0.12.0"));

    assertEquals(PrintAction.IN_PROGRESS, response.getAction());
    assertEquals("job-old", response.getPrintJobId());
    assertNull(response.getBridgeRequest());
    verify(repository, never()).save(any());
  }

  @Test
  void aJobLeftInFlightTooLongExpiresAndANewOneStarts() {
    when(invoiceService.renderInvoiceForPrint("p-1", SHOP))
        .thenReturn(new DotMatrixDocument("TEXT", DotMatrixDocumentKind.INVOICE, "T001148"));
    PrintJob stale = job("job-old", PrintJobStatus.PENDING, NOW.minus(Duration.ofMinutes(5)));
    when(repository.findFirstByShopIdAndSourceAndDocumentIdAndStatusIn(
            eq(SHOP), eq(PrintDocumentSource.SALE), eq("p-1"), any()))
        .thenReturn(Optional.of(stale));

    PrintJobResponse response = service.create(SHOP, "u-1", sale("p-1", true, "0.12.0"));

    assertEquals(PrintJobStatus.EXPIRED, stale.getStatus());
    assertEquals(NOW, stale.getCompletedAt());
    assertEquals(PrintAction.BRIDGE, response.getAction());
    assertEquals("job-new", response.getPrintJobId());
  }

  @Test
  void aRaceOnTheInFlightIndexAnswersWithTheJobThatWon() {
    when(invoiceService.renderInvoiceForPrint("p-1", SHOP))
        .thenReturn(new DotMatrixDocument("TEXT", DotMatrixDocumentKind.INVOICE, "T001148"));
    PrintJob winner = job("job-winner", PrintJobStatus.PENDING, NOW);
    when(repository.findFirstByShopIdAndSourceAndDocumentIdAndStatusIn(
            eq(SHOP), eq(PrintDocumentSource.SALE), eq("p-1"), any()))
        .thenReturn(Optional.empty(), Optional.of(winner));
    when(repository.save(any(PrintJob.class))).thenThrow(new DuplicateKeyException("dup"));

    PrintJobResponse response = service.create(SHOP, "u-1", sale("p-1", true, "0.12.0"));

    assertEquals(PrintAction.IN_PROGRESS, response.getAction());
    assertEquals("job-winner", response.getPrintJobId());
  }

  @Test
  void aRefundPrintsAsTheCreditNoteItIs() {
    when(creditNoteService.renderCustomerCreditNoteForPrint("r-1", SHOP))
        .thenReturn(new DotMatrixDocument("TEXT", DotMatrixDocumentKind.CREDIT_NOTE, "CN-7"));
    CreatePrintJobRequest request = sale("r-1", true, "0.12.0");
    request.setSource(PrintDocumentSource.REFUND);

    PrintJobResponse response = service.create(SHOP, "u-1", request);

    assertEquals("CREDIT_NOTE", response.getBridgeRequest().getDocType());
    assertEquals("credit-note-CN-7.prn", response.getDownload().getFilename());
  }

  @Test
  void aPrinterFaultStaysOnScreenAndKeepsThePrintersError() {
    PrintJob pending = job("job-1", PrintJobStatus.PENDING, NOW);
    when(repository.findByIdAndShopId("job-1", SHOP)).thenReturn(Optional.of(pending));

    PrintOutcomeResponse outcome =
        service.reportOutcome(SHOP, "job-1", report(PrintObservation.FAILED, "j-4", "Out of paper"));

    assertEquals(PrintOutcome.FAILED_PRINTER, outcome.getOutcome());
    assertFalse(outcome.isShouldClose());
    assertTrue(outcome.isRetryable());
    assertFalse(outcome.isDownloadInstead());
    assertEquals("Out of paper", outcome.getError());
    assertEquals("j-4", pending.getBridgeJobId());
    assertEquals(PrintJobStatus.FAILED_PRINTER, pending.getStatus());
  }

  @Test
  void anUnreachableBridgeFallsBackToTheFile() {
    PrintJob pending = job("job-1", PrintJobStatus.PENDING, NOW);
    when(repository.findByIdAndShopId("job-1", SHOP)).thenReturn(Optional.of(pending));

    PrintOutcomeResponse outcome =
        service.reportOutcome(SHOP, "job-1", report(PrintObservation.UNREACHABLE, null, null));

    assertEquals(PrintOutcome.BRIDGE_UNREACHABLE, outcome.getOutcome());
    assertTrue(outcome.isDownloadInstead());
    assertEquals(PrintJobStatus.FAILED_CLIENT, pending.getStatus());
  }

  @Test
  void aRefusalThatMayHavePrintedOffersNoFileThatCouldPrintItTwice() {
    PrintJob pending = job("job-1", PrintJobStatus.PENDING, NOW);
    when(repository.findByIdAndShopId("job-1", SHOP)).thenReturn(Optional.of(pending));

    PrintOutcomeResponse outcome =
        service.reportOutcome(SHOP, "job-1", report(PrintObservation.REJECTED, null, "timeout"));

    assertEquals(PrintOutcome.BRIDGE_REJECTED, outcome.getOutcome());
    assertFalse(outcome.isDownloadInstead());
    assertFalse(outcome.isShouldClose());
  }

  @Test
  void stillQueuedStaysInFlightAndCloses() {
    PrintJob pending = job("job-1", PrintJobStatus.PENDING, NOW);
    when(repository.findByIdAndShopId("job-1", SHOP)).thenReturn(Optional.of(pending));

    PrintOutcomeResponse outcome =
        service.reportOutcome(SHOP, "job-1", report(PrintObservation.STILL_QUEUED, "j-5", null));

    assertEquals(PrintOutcome.STILL_QUEUED, outcome.getOutcome());
    assertTrue(outcome.isShouldClose());
    assertEquals(PrintJobStatus.SUBMITTED, pending.getStatus());
    assertNull(pending.getCompletedAt());
  }

  @Test
  void aSecondReportOnASettledJobChangesNothing() {
    PrintJob printed = job("job-1", PrintJobStatus.PRINTED, NOW);
    when(repository.findByIdAndShopId("job-1", SHOP)).thenReturn(Optional.of(printed));

    PrintOutcomeResponse outcome =
        service.reportOutcome(SHOP, "job-1", report(PrintObservation.FAILED, null, "late"));

    assertEquals(PrintOutcome.PRINTED, outcome.getOutcome());
    assertEquals(PrintJobStatus.PRINTED, printed.getStatus());
    verify(repository, never()).save(any());
  }

  @Test
  void theFileIsNamedAfterTheNumberWithUnsafeCharactersReplaced() {
    PrintJob job = job("job-1", PrintJobStatus.PENDING, NOW);
    job.setDocumentKind(DotMatrixDocumentKind.DEBIT_NOTE);
    job.setDocumentNumber("DN/00004 A");
    assertEquals("debit-note-DN-00004-A.prn", PrintJobService.filenameFor(job));

    job.setDocumentNumber(null);
    job.setDocumentId("ret-9");
    assertEquals("debit-note-ret-9.prn", PrintJobService.filenameFor(job));
  }

  private PrintJob saved() {
    org.mockito.ArgumentCaptor<PrintJob> captor = org.mockito.ArgumentCaptor.forClass(PrintJob.class);
    verify(repository).save(captor.capture());
    PrintJob job = captor.getValue();
    assertNotNull(job.getCreatedAt());
    return job;
  }

  private static CreatePrintJobRequest sale(String id, boolean reachable, String version) {
    CreatePrintJobRequest request = new CreatePrintJobRequest();
    request.setSource(PrintDocumentSource.SALE);
    request.setDocumentId(id);
    request.setBridge(new PrintBridgeObservation(reachable, version, "TVS MSP 240 Star"));
    return request;
  }

  private static ReportPrintOutcomeRequest report(
      PrintObservation observation, String bridgeJobId, String error) {
    ReportPrintOutcomeRequest request = new ReportPrintOutcomeRequest();
    request.setObservation(observation);
    request.setBridgeJobId(bridgeJobId);
    request.setError(error);
    return request;
  }

  private static PrintJob job(String id, PrintJobStatus status, Instant createdAt) {
    PrintJob job = new PrintJob();
    job.setId(id);
    job.setShopId(SHOP);
    job.setSource(PrintDocumentSource.SALE);
    job.setDocumentId("p-1");
    job.setDocumentKind(DotMatrixDocumentKind.INVOICE);
    job.setStatus(status);
    job.setCreatedAt(createdAt);
    return job;
  }
}
