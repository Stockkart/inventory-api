package com.inventory.product.service.vertical;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.documentservice.domain.KotStamp;
import com.inventory.documentservice.rest.dto.GenerateKotRequest;
import com.inventory.documentservice.rest.dto.KotItem;
import com.inventory.documentservice.service.KotPdfService;
import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.kot.CafeKotPort;
import com.inventory.pluginengine.kot.CafeKotTicket;
import com.inventory.pluginengine.kot.CafeKotTicketLine;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Renders a cafe kitchen ticket's document, and reprints one.
 *
 * <p>This service never prints — the frontend fetches the document and does that.
 *
 * <p>The ticket entities live in the cafe plugin and are reached through {@link CafeKotPort},
 * which {@code core/product} does not implement. This class adds the one thing the plugin cannot
 * see: PDF rendering, because {@code plugins/cafe} does not depend on {@code core/documentservice}.
 */
@Service
public class CafeKotService {

  private static final String VERTICAL_ID = "cafe";
  private static final DateTimeFormatter PRINTED_AT = DateTimeFormatter.ofPattern("dd/MM HH:mm");

  private final PluginRegistry pluginRegistry;
  private final KotPdfService kotPdfService;

  public CafeKotService(PluginRegistry pluginRegistry, KotPdfService kotPdfService) {
    this.pluginRegistry = pluginRegistry;
    this.kotPdfService = kotPdfService;
  }

  private CafeKotPort port() {
    return pluginRegistry
        .require(VERTICAL_ID)
        .getCafeKotPort()
        .orElseThrow(
            () -> new ValidationException("This shop's vertical does not support kitchen tickets"));
  }

  /**
   * Re-issues an already-issued ticket's document for the frontend to fetch and print again.
   * Bumps the ticket's {@code reprintCount} through the port; creates no new ticket. The caller
   * ({@link com.inventory.product.rest.controller.CafeKotController}) has already rejected a
   * blank {@code Idempotency-Key} before this is reached.
   *
   * <p>Renders {@code REPRINT} so a cook cannot mistake the slip for a second order — unless the
   * ticket is already cancelled, in which case it renders {@code CANCELLED} still: that is already
   * unmistakable, and stamping {@code REPRINT} over it would read as a fresh order for a dead
   * ticket, while a second {@code CANCELLED}-of-{@code CANCELLED} stamp would read as a second,
   * unrelated cancellation.
   */
  public byte[] reprint(String shopId, String kotId, String idempotencyKey) {
    CafeKotTicket ticket = port().reprint(shopId, kotId, idempotencyKey);
    KotStamp stamp = isCancelled(ticket) ? KotStamp.CANCELLED : KotStamp.REPRINT;
    return kotPdfService.generateKotPdf(toDocumentRequest(ticket, stamp));
  }

  /** The ticket's document. Renders unstamped for an issued ticket, CANCELLED for a voided one. */
  public byte[] kotDocument(String shopId, String kotId) {
    CafeKotTicket ticket =
        port()
            .findKot(shopId, kotId)
            .orElseThrow(() -> new ResourceNotFoundException("CafeKot", "id", kotId));
    KotStamp stamp = isCancelled(ticket) ? KotStamp.CANCELLED : KotStamp.NONE;
    return kotPdfService.generateKotPdf(toDocumentRequest(ticket, stamp));
  }

  /**
   * A ticket cancels if its {@code kind} says so, and that is the whole rule — no legacy branch
   * in the document path, as the spec requires.
   *
   * <p>There used to be a second clause for {@code status == "VOIDED"}, left by the retired
   * running-order path. Nothing on this branch writes that status: the only thing that stops food
   * is {@code CafeKotCancelService}, and every ticket it writes carries {@code kind == CANCEL}.
   */
  private static boolean isCancelled(CafeKotTicket ticket) {
    return "CANCEL".equals(ticket.getKind());
  }

  private GenerateKotRequest toDocumentRequest(CafeKotTicket ticket, KotStamp stamp) {
    GenerateKotRequest request = new GenerateKotRequest();
    request.setKotNo(ticket.getKotNo());
    request.setDepartment(ticket.getDepartment());
    request.setRoundNo(ticket.getRoundNo());
    request.setTableLabel(ticket.getTableLabel());
    request.setTokenNo(ticket.getTokenNo());
    request.setPrintedAt(LocalDateTime.now().format(PRINTED_AT));
    request.setStamp(stamp);
    request.setItems(
        (ticket.getLines() == null ? List.<CafeKotTicketLine>of() : ticket.getLines())
            .stream()
                .map(
                    line -> {
                      KotItem item = new KotItem();
                      item.setName(line.getName());
                      item.setQuantity(line.getQuantity());
                      item.setNote(line.getNote());
                      return item;
                    })
                .toList());
    return request;
  }
}
