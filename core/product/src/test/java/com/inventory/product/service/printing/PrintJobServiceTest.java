package com.inventory.product.service.printing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.documentservice.api.DotMatrixDocument;
import com.inventory.documentservice.api.DotMatrixDocumentKind;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.product.config.PrintBridgeProperties;
import com.inventory.product.domain.model.enums.PrintAction;
import com.inventory.product.domain.model.enums.PrintActionReason;
import com.inventory.product.domain.model.enums.PrintDocumentSource;
import com.inventory.product.domain.model.enums.PrintObservation;
import com.inventory.product.domain.model.enums.PrintOutcome;
import com.inventory.product.rest.dto.request.CreatePrintJobRequest;
import com.inventory.product.rest.dto.request.PrintBridgeObservation;
import com.inventory.product.rest.dto.request.ReportPrintOutcomeRequest;
import com.inventory.product.rest.dto.response.PrintJobResponse;
import com.inventory.product.rest.dto.response.PrintOutcomeResponse;
import com.inventory.product.service.CreditNoteService;
import com.inventory.product.service.InvoiceService;
import com.inventory.product.validation.PrintJobValidator;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Every print decision is made here; the browser only carries the answers. Nothing is stored. */
class PrintJobServiceTest {

  private static final String SHOP = "shop-1";

  private InvoiceService invoiceService;
  private CreditNoteService creditNoteService;
  private MetricsWrapper metrics;
  private PrintJobService service;

  @BeforeEach
  void setUp() {
    invoiceService = mock(InvoiceService.class);
    creditNoteService = mock(CreditNoteService.class);
    metrics = mock(MetricsWrapper.class);
    PrintBridgeProperties properties = new PrintBridgeProperties();
    properties.setMinimumVersion("0.11.0");
    properties.setInFlightTimeout(Duration.ofMinutes(2));
    PrintJobValidator validator = new PrintJobValidator();
    service =
        new PrintJobService(
            new PrintBridgeService(properties, validator, metrics),
            properties,
            validator,
            invoiceService,
            creditNoteService,
            metrics);
  }

  @Test
  void aConnectedBridgeGetsTheTextWithTheKindTheRendererDecided() {
    when(invoiceService.renderInvoiceForPrint("p-1", SHOP))
        .thenReturn(new DotMatrixDocument("TEXT", DotMatrixDocumentKind.ESTIMATE, "EST/00022"));

    PrintJobResponse response = service.create(SHOP, sale("p-1", true, "0.12.0"));

    assertEquals(PrintAction.BRIDGE, response.getAction());
    assertNull(response.getReason());
    assertEquals("ESTIMATE", response.getBridgeRequest().getDocType());
    assertEquals("p-1", response.getBridgeRequest().getDocId());
    assertEquals(0, response.getBridgeRequest().getCopies());
    assertEquals("TEXT", response.getBridgeRequest().getText());
    assertEquals("EST/00022", response.getDocumentNumber());
    assertEquals("estimate-EST-00022.prn", response.getDownload().getFilename());
    assertEquals(5000, response.getPoll().getBudgetMs());
    assertEquals(120_000, response.getInFlightTtlMs());
    verify(metrics)
        .record(
            "inventory_product_print_jobs_total", 1, "module", "product",
            "operation", "sale", "outcome", "bridge");
  }

  @Test
  void noBridgeDownloadsAndSaysWhy() {
    when(invoiceService.renderInvoiceForPrint("p-1", SHOP))
        .thenReturn(new DotMatrixDocument("TEXT", DotMatrixDocumentKind.INVOICE, "T001148"));

    PrintJobResponse response = service.create(SHOP, sale("p-1", false, null));

    assertEquals(PrintAction.DOWNLOAD, response.getAction());
    assertEquals(PrintActionReason.BRIDGE_UNAVAILABLE, response.getReason());
    assertNull(response.getBridgeRequest());
    assertEquals("invoice-T001148.prn", response.getDownload().getFilename());
    verify(metrics)
        .record(
            "inventory_product_print_jobs_total", 1, "module", "product",
            "operation", "sale", "outcome", "download");
  }

  @Test
  void aDocumentThisBrowserSentRecentlyIsNotSentAgain() {
    when(invoiceService.renderInvoiceForPrint("p-1", SHOP))
        .thenReturn(new DotMatrixDocument("TEXT", DotMatrixDocumentKind.INVOICE, "T001148"));
    CreatePrintJobRequest request = sale("p-1", true, "0.12.0");
    request.setPendingAgeMs(10_000L);

    PrintJobResponse response = service.create(SHOP, request);

    assertEquals(PrintAction.IN_PROGRESS, response.getAction());
    assertEquals(PrintActionReason.PRINT_IN_PROGRESS, response.getReason());
    assertEquals("T001148", response.getDocumentNumber());
    assertNull(response.getBridgeRequest());
    assertNull(response.getDownload());
    verify(metrics)
        .record(
            "inventory_product_print_jobs_total", 1, "module", "product",
            "operation", "sale", "outcome", "in_progress");
  }

  @Test
  void aPendingJobPastTheInFlightTimeDoesNotBlockANewPrint() {
    when(invoiceService.renderInvoiceForPrint("p-1", SHOP))
        .thenReturn(new DotMatrixDocument("TEXT", DotMatrixDocumentKind.INVOICE, "T001148"));
    CreatePrintJobRequest request = sale("p-1", true, "0.12.0");
    request.setPendingAgeMs(120_000L);

    assertEquals(PrintAction.BRIDGE, service.create(SHOP, request).getAction());
  }

  @Test
  void aNegativePendingAgeIsRefused() {
    CreatePrintJobRequest request = sale("p-1", true, "0.12.0");
    request.setPendingAgeMs(-1L);

    assertThrows(ValidationException.class, () -> service.create(SHOP, request));
  }

  @Test
  void aRefundPrintsAsTheCreditNoteItIs() {
    when(creditNoteService.renderCustomerCreditNoteForPrint("r-1", SHOP))
        .thenReturn(new DotMatrixDocument("TEXT", DotMatrixDocumentKind.CREDIT_NOTE, "CN-7"));
    CreatePrintJobRequest request = sale("r-1", true, "0.12.0");
    request.setSource(PrintDocumentSource.REFUND);

    PrintJobResponse response = service.create(SHOP, request);

    assertEquals("CREDIT_NOTE", response.getBridgeRequest().getDocType());
    assertEquals("credit-note-CN-7.prn", response.getDownload().getFilename());
  }

  @Test
  void aPrinterFaultStaysOnScreenAndKeepsThePrintersError() {
    PrintOutcomeResponse outcome =
        service.reportOutcome(SHOP, report(PrintObservation.FAILED, "j-4", "Out of paper"));

    assertEquals(PrintOutcome.FAILED_PRINTER, outcome.getOutcome());
    assertFalse(outcome.isShouldClose());
    assertTrue(outcome.isRetryable());
    assertFalse(outcome.isDownloadInstead());
    assertEquals("Out of paper", outcome.getError());
    verify(metrics)
        .record(
            "inventory_product_print_job_outcomes_total", 1, "module", "product",
            "operation", "sale", "outcome", "failed_printer");
  }

  @Test
  void anUnreachableBridgeFallsBackToTheFile() {
    PrintOutcomeResponse outcome =
        service.reportOutcome(SHOP, report(PrintObservation.UNREACHABLE, null, null));

    assertEquals(PrintOutcome.BRIDGE_UNREACHABLE, outcome.getOutcome());
    assertTrue(outcome.isDownloadInstead());
  }

  @Test
  void aRefusalThatMayHavePrintedOffersNoFileThatCouldPrintItTwice() {
    PrintOutcomeResponse outcome =
        service.reportOutcome(SHOP, report(PrintObservation.REJECTED, null, "timeout"));

    assertEquals(PrintOutcome.BRIDGE_REJECTED, outcome.getOutcome());
    assertFalse(outcome.isDownloadInstead());
    assertFalse(outcome.isShouldClose());
  }

  @Test
  void stillQueuedCloses() {
    PrintOutcomeResponse outcome =
        service.reportOutcome(SHOP, report(PrintObservation.STILL_QUEUED, "j-5", null));

    assertEquals(PrintOutcome.STILL_QUEUED, outcome.getOutcome());
    assertTrue(outcome.isShouldClose());
    assertFalse(outcome.isRetryable());
  }

  @Test
  void aJobNobodySawFinishIsUnconfirmedNotFailed() {
    PrintOutcomeResponse outcome =
        service.reportOutcome(SHOP, report(PrintObservation.EXPIRED, "j-5", null));

    assertEquals(PrintOutcome.UNCONFIRMED, outcome.getOutcome());
    assertFalse(outcome.isRetryable());
    assertFalse(outcome.isDownloadInstead());
    verify(metrics)
        .record(
            "inventory_product_print_job_outcomes_total", 1, "module", "product",
            "operation", "sale", "outcome", "unconfirmed");
  }

  @Test
  void anOutcomeWithoutASourceIsRefused() {
    ReportPrintOutcomeRequest request = report(PrintObservation.PRINTED, "j-1", null);
    request.setSource(null);

    assertThrows(ValidationException.class, () -> service.reportOutcome(SHOP, request));
  }

  @Test
  void theFileIsNamedAfterTheNumberWithUnsafeCharactersReplaced() {
    assertEquals(
        "debit-note-DN-00004-A.prn",
        PrintJobService.filenameFor(
            new DotMatrixDocument("T", DotMatrixDocumentKind.DEBIT_NOTE, "DN/00004 A"), "ret-9"));
    assertEquals(
        "debit-note-ret-9.prn",
        PrintJobService.filenameFor(
            new DotMatrixDocument("T", DotMatrixDocumentKind.DEBIT_NOTE, null), "ret-9"));
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
    request.setSource(PrintDocumentSource.SALE);
    request.setObservation(observation);
    request.setBridgeJobId(bridgeJobId);
    request.setError(error);
    return request;
  }
}
