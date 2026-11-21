package com.inventory.notifications.service;

import com.inventory.notifications.api.port.AttachmentPort;
import com.inventory.notifications.api.Attachment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.metrics.MetricsWrapper;
import com.inventory.notifications.adapter.ChannelAdapter;
import com.inventory.notifications.adapter.SendResult;
import com.inventory.notifications.constants.MessagingConstants;
import com.inventory.notifications.domain.model.MessageChannel;
import com.inventory.notifications.domain.model.MessageStatus;
import com.inventory.notifications.domain.model.OutboundMessage;
import com.inventory.notifications.domain.repository.OutboundMessageRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/** Send-time attachments (HR decision 1): ≤ 10 renders per tick, bytes never kept. */
class MessageQueueProcessorTest {

  private final OutboundMessageRepository repository = mock(OutboundMessageRepository.class);
  private final ChannelAdapter email = mock(ChannelAdapter.class);
  private final AttachmentPort resolver = mock(AttachmentPort.class);
  private final List<OutboundMessage> saved = new ArrayList<>();
  private MessageQueueProcessor processor;

  @BeforeEach
  void setUp() {
    processor = new MessageQueueProcessor();
    ReflectionTestUtils.setField(processor, "outboundMessageRepository", repository);
    ReflectionTestUtils.setField(processor, "adapters", List.of(email));
    ReflectionTestUtils.setField(processor, "attachmentResolvers", List.of(resolver));
    ReflectionTestUtils.setField(processor, "metrics", mock(MetricsWrapper.class));
    when(email.getChannel()).thenReturn(MessageChannel.EMAIL);
    when(resolver.supports("PAYSLIP")).thenReturn(true);
    when(repository.save(any(OutboundMessage.class))).thenAnswer(inv -> {
      OutboundMessage m = inv.getArgument(0);
      // what would be stored at this moment
      OutboundMessage copy = OutboundMessage.builder().id(m.getId()).status(m.getStatus())
          .retryCount(m.getRetryCount()).attachments(m.getAttachments()).build();
      saved.add(copy);
      return m;
    });
  }

  private static OutboundMessage payslip(int i) {
    return OutboundMessage.builder().id("m" + i).channel(MessageChannel.EMAIL)
        .recipient("staff" + i + "@example.com").subject("Payslip").body("<p>hi</p>")
        .status(MessageStatus.PENDING).createdAt(Instant.EPOCH)
        .attachmentRef(new OutboundMessage.AttachmentRef("PAYSLIP", "r1", "l" + i, "s1"))
        .build();
  }

  private void pending(List<OutboundMessage> messages) {
    when(repository.findPendingPlainForDispatch(eq(MessageStatus.PENDING), anyInt(),
        any(Instant.class), any(Pageable.class))).thenReturn(messages.stream()
        .filter(m -> m.getAttachmentRef() == null).toList());
    when(repository.findPendingWithAttachmentRefForDispatch(eq(MessageStatus.PENDING), anyInt(),
        any(Instant.class), any(Pageable.class))).thenReturn(messages.stream()
        .filter(m -> m.getAttachmentRef() != null).toList());
  }

  @Test
  void plainEmailsGoOutAlongsideAQueueOfPayslips() {
    List<OutboundMessage> batch = new ArrayList<>();
    for (int i = 0; i < 15; i++) {
      batch.add(payslip(i));
    }
    OutboundMessage reset = OutboundMessage.builder().id("reset").channel(MessageChannel.EMAIL)
        .recipient("owner@example.com").subject("Reset").body("<p>x</p>")
        .status(MessageStatus.PENDING).createdAt(Instant.EPOCH).build();
    batch.add(reset);
    pending(batch);
    when(resolver.resolve(any())).thenReturn(Optional.of(
        new Attachment("payslip.pdf", "application/pdf", "JVBERi0=")));
    when(email.send(any())).thenReturn(SendResult.success("ext"));

    processor.processQueue();

    assertEquals(MessageStatus.SENT, reset.getStatus());
    verify(email, times(11)).send(any());
  }

  @Test
  void rendersAtMostTenPerTickAndLeavesTheRestUntouched() {
    List<OutboundMessage> batch = new ArrayList<>();
    for (int i = 0; i < 15; i++) {
      batch.add(payslip(i));
    }
    pending(batch);
    when(resolver.resolve(any())).thenReturn(Optional.of(
        new Attachment("payslip.pdf", "application/pdf", "JVBERi0=")));
    when(email.send(any())).thenReturn(SendResult.success("ext"));

    processor.processQueue();

    verify(resolver, times(MessagingConstants.MAX_RENDERS_PER_TICK)).resolve(any());
    verify(email, times(10)).send(any());
    assertEquals(10, saved.size(), "the five left over are not saved, so no retry is counted");
    for (OutboundMessage m : saved) {
      assertEquals(MessageStatus.SENT, m.getStatus());
      assertNull(m.getAttachments(), "the PDF is not stored after sending");
    }
    for (int i = 10; i < 15; i++) {
      assertEquals(0, batch.get(i).getRetryCount());
      assertEquals(MessageStatus.PENDING, batch.get(i).getStatus());
    }
  }

  @Test
  void theRenderedFileGoesToTheAdapter() {
    pending(List.of(payslip(1)));
    when(resolver.resolve(any())).thenReturn(Optional.of(
        new Attachment("payslip.pdf", "application/pdf", "JVBERi0=")));
    List<String> sentFiles = new ArrayList<>();
    when(email.send(any())).thenAnswer(inv -> {
      // the processor clears the bytes after a successful send: look at them during it
      OutboundMessage m = inv.getArgument(0);
      m.getAttachments().forEach(a -> sentFiles.add(a.getFileName()));
      return SendResult.success("ext");
    });

    processor.processQueue();

    assertEquals(List.of("payslip.pdf"), sentFiles);
  }

  @Test
  void aFailedSendRetriesWithoutKeepingTheRenderedBytes() {
    pending(List.of(payslip(1)));
    when(resolver.resolve(any())).thenReturn(Optional.of(
        new Attachment("payslip.pdf", "application/pdf", "JVBERi0=")));
    when(email.send(any())).thenReturn(SendResult.failure("provider down"));

    processor.processQueue();

    assertEquals(1, saved.size());
    assertEquals(1, saved.get(0).getRetryCount());
    assertNull(saved.get(0).getAttachments());
  }

  @Test
  void aDocumentThatCannotBeRenderedIsRetriedLater() {
    pending(List.of(payslip(1)));
    when(resolver.resolve(any())).thenReturn(Optional.empty());

    processor.processQueue();

    verify(email, times(0)).send(any());
    assertEquals(1, saved.get(0).getRetryCount());
  }
}
