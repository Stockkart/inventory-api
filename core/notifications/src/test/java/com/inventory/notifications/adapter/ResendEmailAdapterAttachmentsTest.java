package com.inventory.notifications.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.notifications.domain.model.OutboundMessage;
import com.resend.services.emails.model.Attachment;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Outbox attachments become Resend attachments (base64 content, file name, content type). */
class ResendEmailAdapterAttachmentsTest {

  @Test
  void mapsEachFileAndSkipsEmptyOnes() {
    OutboundMessage m = OutboundMessage.builder()
        .attachments(Arrays.asList(
            new OutboundMessage.Attachment("payslip.pdf", "application/pdf", "JVBERi0="),
            new OutboundMessage.Attachment("empty.pdf", "application/pdf", " "),
            null))
        .build();

    List<Attachment> files = ResendEmailAdapter.attachments(m);

    assertEquals(1, files.size());
    assertEquals("payslip.pdf", files.get(0).getFileName());
    assertEquals("application/pdf", files.get(0).getContentType());
    assertEquals("JVBERi0=", files.get(0).getContent());
  }

  @Test
  void noAttachmentsIsAnEmptyList() {
    assertTrue(ResendEmailAdapter.attachments(OutboundMessage.builder().build()).isEmpty());
  }
}
