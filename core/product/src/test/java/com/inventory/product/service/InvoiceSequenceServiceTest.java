package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.product.domain.model.InvoiceSequence;
import com.inventory.product.domain.model.InvoiceSequenceSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class InvoiceSequenceServiceTest {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final String SHOP = "shop1";

  @Mock
  private MongoTemplate mongoTemplate;

  private InvoiceSequenceService service;
  private Clock clock;

  @BeforeEach
  void setUp() {
    clock = Clock.fixed(LocalDate.of(2026, 8, 3).atStartOfDay(IST).toInstant(), IST);
    service = new InvoiceSequenceService(mongoTemplate, clock);
  }

  @Test
  void getNextInvoiceNo_firstIssue_usesStockKartDefault() {
    when(mongoTemplate.findById(SHOP, InvoiceSequence.class)).thenReturn(null);
    InvoiceSequence created = regularDoc(1, "2026-27", null);
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(InvoiceSequence.class)))
        .thenReturn(created);
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(InvoiceSequence.class)))
        .thenReturn(null);

    String no = service.getNextInvoiceNo(SHOP);
    assertEquals("INV-00001", no);
  }

  @Test
  void getNextInvoiceNo_sameFy_increments() {
    InvoiceSequence existing = regularDoc(12, "2026-27", Instant.parse("2026-05-01T00:00:00Z"));
    existing.setPrefix("SL-");
    existing.setPadLength(4);
    when(mongoTemplate.findById(SHOP, InvoiceSequence.class)).thenReturn(existing);
    InvoiceSequence next = regularDoc(13, "2026-27", existing.getLockedAt());
    next.setPrefix("SL-");
    next.setPadLength(4);
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(InvoiceSequence.class)))
        .thenReturn(next);

    assertEquals("SL-0013", service.getNextInvoiceNo(SHOP));
  }

  @Test
  void getNextInvoiceNo_newFy_resetsToOne() {
    InvoiceSequence existing = regularDoc(412, "2025-26", Instant.parse("2025-06-01T00:00:00Z"));
    when(mongoTemplate.findById(SHOP, InvoiceSequence.class)).thenReturn(existing);
    InvoiceSequence rolled = regularDoc(1, "2026-27", existing.getLockedAt());
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(InvoiceSequence.class)))
        .thenReturn(rolled);

    assertEquals("INV-00001", service.getNextInvoiceNo(SHOP));

    ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate)
        .findAndModify(any(Query.class), updateCaptor.capture(), any(FindAndModifyOptions.class), eq(InvoiceSequence.class));
  }

  @Test
  void peekNext_afterMigrateSeed() {
    InvoiceSequence existing = regularDoc(152, "2026-27", null);
    existing.setPrefix("SL-");
    existing.setPadLength(4);
    when(mongoTemplate.findById(SHOP, InvoiceSequence.class)).thenReturn(existing);
    assertEquals("SL-0153", service.peekNextRegularInvoiceNo(SHOP));
    verify(mongoTemplate, never())
        .findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(InvoiceSequence.class));
  }

  private static InvoiceSequence regularDoc(long seq, String fy, Instant lockedAt) {
    InvoiceSequence doc = new InvoiceSequence();
    doc.setShopId(SHOP);
    doc.setSeq(seq);
    doc.setFyLabel(fy);
    doc.setPrefix(InvoiceSequenceService.DEFAULT_REGULAR_PREFIX);
    doc.setPadLength(InvoiceSequenceService.DEFAULT_PAD_LENGTH);
    doc.setSource(InvoiceSequenceSource.STOCKKART);
    doc.setLockedAt(lockedAt);
    return doc;
  }
}
