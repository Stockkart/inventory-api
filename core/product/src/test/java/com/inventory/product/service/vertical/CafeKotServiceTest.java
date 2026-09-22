package com.inventory.product.service.vertical;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.documentservice.domain.KotStamp;
import com.inventory.documentservice.rest.dto.GenerateKotRequest;
import com.inventory.documentservice.service.KotPdfService;
import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.VerticalPlugin;
import com.inventory.pluginengine.kot.CafeKotPort;
import com.inventory.pluginengine.kot.CafeKotTicket;
import com.inventory.pluginengine.kot.CafeKotTicketLine;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CafeKotServiceTest {

  private CafeKotPort port;
  private KotPdfService kotPdfService;
  private CafeKotService service;

  @BeforeEach
  void setUp() {
    port = mock(CafeKotPort.class);
    kotPdfService = mock(KotPdfService.class);

    PluginRegistry registry = mock(PluginRegistry.class);
    VerticalPlugin plugin = mock(VerticalPlugin.class);
    when(plugin.getCafeKotPort()).thenReturn(Optional.of(port));
    when(registry.require("cafe")).thenReturn(plugin);

    service = new CafeKotService(registry, kotPdfService);
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
  void documentCarriesTableAndTokenForADineInPunch() {
    CafeKotTicket ticket =
        CafeKotTicket.builder()
            .kotId("k1")
            .shopId("s1")
            .kind("ISSUE")
            .kotNo(41)
            .department("KITCHEN")
            .roundNo(1)
            .tableLabel("T4")
            .tokenNo("12")
            .lines(List.of(CafeKotTicketLine.builder().name("Tea").quantity(2).build()))
            .build();
    when(port.findKot("s1", "k1")).thenReturn(Optional.of(ticket));
    when(kotPdfService.generateKotPdf(any())).thenReturn(new byte[] {1});

    service.kotDocument("s1", "k1");

    ArgumentCaptor<GenerateKotRequest> captor = ArgumentCaptor.forClass(GenerateKotRequest.class);
    verify(kotPdfService).generateKotPdf(captor.capture());
    assertEquals("T4", captor.getValue().getTableLabel());
    assertEquals("12", captor.getValue().getTokenNo());
  }

  @Test
  void documentRendersCancelledForALegacyVoidedTicketWithNoKind() {
    // The running-order path this ticket predates set no `kind`; only `status` says VOIDED.
    CafeKotTicket ticket =
        CafeKotTicket.builder().kotId("k3").shopId("s1").status("VOIDED").lines(List.of()).build();
    when(port.findKot("s1", "k3")).thenReturn(Optional.of(ticket));
    when(kotPdfService.generateKotPdf(any())).thenReturn(new byte[] {1});

    service.kotDocument("s1", "k3");

    ArgumentCaptor<GenerateKotRequest> captor = ArgumentCaptor.forClass(GenerateKotRequest.class);
    verify(kotPdfService).generateKotPdf(captor.capture());
    assertEquals(KotStamp.CANCELLED, captor.getValue().getStamp());
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

  @Test
  void reprintOfAnUnknownTicketIsResourceNotFound() {
    when(port.reprint("s1", "missing", "idem-1"))
        .thenThrow(new ResourceNotFoundException("CafeKot", "id", "missing"));

    assertThrows(ResourceNotFoundException.class, () -> service.reprint("s1", "missing", "idem-1"));
  }

  @Test
  void aReprintStampsTheSlipAndCreatesNoNewTicket() {
    CafeKotTicket ticket =
        CafeKotTicket.builder()
            .kotId("k1")
            .shopId("s1")
            .kind("ISSUE")
            .kotNo(7)
            .department("KITCHEN")
            .roundNo(1)
            .reprintCount(1)
            .lines(List.of(CafeKotTicketLine.builder().name("Tea").quantity(2).build()))
            .build();
    when(port.reprint("s1", "k1", "idem-1")).thenReturn(ticket);
    when(kotPdfService.generateKotPdf(any())).thenReturn(new byte[] {9});

    byte[] result = service.reprint("s1", "k1", "idem-1");

    assertArrayEquals(new byte[] {9}, result);
    ArgumentCaptor<GenerateKotRequest> captor = ArgumentCaptor.forClass(GenerateKotRequest.class);
    verify(kotPdfService).generateKotPdf(captor.capture());
    assertEquals(KotStamp.REPRINT, captor.getValue().getStamp());
    assertEquals(7, captor.getValue().getKotNo());
    // The port's reprint is the only ticket-producing call: no findKot, flush, or cancel — a
    // reprint never creates a new ticket, only bumps the count on the existing one.
    verify(port).reprint("s1", "k1", "idem-1");
    verify(port, never()).findKot(any(), any());
    verifyNoMoreInteractions(port);
  }

  @Test
  void aReprintIncrementsTheCount() {
    // The count itself is bumped by the port (CafeKotAdapter); this asserts the service reads it
    // back off the ticket the port returns rather than assuming or recomputing a value.
    CafeKotTicket ticket =
        CafeKotTicket.builder()
            .kotId("k1")
            .shopId("s1")
            .kind("ISSUE")
            .kotNo(7)
            .department("KITCHEN")
            .reprintCount(3)
            .lines(List.of())
            .build();
    when(port.reprint("s1", "k1", "idem-1")).thenReturn(ticket);
    when(kotPdfService.generateKotPdf(any())).thenReturn(new byte[] {1});

    service.reprint("s1", "k1", "idem-1");

    verify(port).reprint("s1", "k1", "idem-1");
    assertEquals(3, ticket.getReprintCount());
  }

  @Test
  void aCancelledTicketStillRendersCancelledWhenReprinted() {
    CafeKotTicket ticket =
        CafeKotTicket.builder()
            .kotId("k2")
            .shopId("s1")
            .kind("CANCEL")
            .reprintCount(1)
            .lines(List.of())
            .build();
    when(port.reprint("s1", "k2", "idem-1")).thenReturn(ticket);
    when(kotPdfService.generateKotPdf(any())).thenReturn(new byte[] {1});

    service.reprint("s1", "k2", "idem-1");

    ArgumentCaptor<GenerateKotRequest> captor = ArgumentCaptor.forClass(GenerateKotRequest.class);
    verify(kotPdfService).generateKotPdf(captor.capture());
    // Not REPRINT, and not a second CANCELLED-of-CANCELLED signal either: the ticket is already
    // unmistakably dead, and stamping REPRINT over it would read as a fresh order.
    assertEquals(KotStamp.CANCELLED, captor.getValue().getStamp());
  }
}
