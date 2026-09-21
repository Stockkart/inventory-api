package com.inventory.product.service.vertical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.documentservice.domain.KotStamp;
import com.inventory.documentservice.rest.dto.GenerateKotRequest;
import com.inventory.documentservice.service.KotPdfService;
import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.VerticalPlugin;
import com.inventory.pluginengine.kot.CafeKotPunchPort;
import com.inventory.pluginengine.kot.CafeKotTicket;
import com.inventory.pluginengine.kot.CafeKotTicketLine;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CafeKotServiceTest {

  private CafeKotPunchPort port;
  private KotPdfService kotPdfService;
  private CafeKotService service;

  @BeforeEach
  void setUp() {
    port = mock(CafeKotPunchPort.class);
    kotPdfService = mock(KotPdfService.class);

    PluginRegistry registry = mock(PluginRegistry.class);
    VerticalPlugin plugin = mock(VerticalPlugin.class);
    when(plugin.getCafeKotPunchPort()).thenReturn(Optional.of(port));
    when(registry.require("cafe")).thenReturn(plugin);

    service = new CafeKotService(registry, kotPdfService);
  }

  @Test
  void punchReturnsCreatedTickets() {
    CafeKotTicket ticket = CafeKotTicket.builder().kotId("k1").shopId("s1").build();
    when(port.punch("s1", "u1", "p1", "idem-1")).thenReturn(List.of(ticket));

    List<CafeKotTicket> result = service.punch("s1", "u1", "p1", "idem-1");

    assertEquals(1, result.size());
    assertEquals("k1", result.get(0).getKotId());
    verify(port).punch("s1", "u1", "p1", "idem-1");
  }

  @Test
  void punchRejectsBlankIdempotencyKey() {
    assertThrows(ValidationException.class, () -> service.punch("s1", "u1", "p1", "  "));
    assertThrows(ValidationException.class, () -> service.punch("s1", "u1", "p1", null));
    verify(port, never()).punch(any(), any(), any(), any());
  }

  @Test
  void documentRendersUnstampedForIssue() {
    CafeKotTicket ticket =
        CafeKotTicket.builder()
            .kotId("k1")
            .shopId("s1")
            .kind("ISSUE")
            .kotNo(7)
            .department("KITCHEN")
            .roundNo(1)
            .lines(List.of(CafeKotTicketLine.builder().name("Tea").quantity(2).build()))
            .build();
    when(port.findKot("s1", "k1")).thenReturn(Optional.of(ticket));
    when(kotPdfService.generateKotPdf(any())).thenReturn(new byte[] {1});

    byte[] result = service.kotDocument("s1", "k1");

    assertEquals(1, result.length);
    ArgumentCaptor<GenerateKotRequest> captor = ArgumentCaptor.forClass(GenerateKotRequest.class);
    verify(kotPdfService).generateKotPdf(captor.capture());
    assertEquals(KotStamp.NONE, captor.getValue().getStamp());
    assertEquals(7, captor.getValue().getKotNo());
  }

  @Test
  void documentRendersCancelledForCancel() {
    CafeKotTicket ticket =
        CafeKotTicket.builder().kotId("k2").shopId("s1").kind("CANCEL").lines(List.of()).build();
    when(port.findKot("s1", "k2")).thenReturn(Optional.of(ticket));
    when(kotPdfService.generateKotPdf(any())).thenReturn(new byte[] {1});

    service.kotDocument("s1", "k2");

    ArgumentCaptor<GenerateKotRequest> captor = ArgumentCaptor.forClass(GenerateKotRequest.class);
    verify(kotPdfService).generateKotPdf(captor.capture());
    assertEquals(KotStamp.CANCELLED, captor.getValue().getStamp());
  }

  @Test
  void unknownTicketIsResourceNotFound() {
    when(port.findKot("s1", "missing")).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> service.kotDocument("s1", "missing"));
  }

  @Test
  void otherShopsTicketResolvesToNothing() {
    when(port.findKot("other-shop", "k1")).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> service.kotDocument("other-shop", "k1"));
    verify(port).findKot("other-shop", "k1");
  }
}
