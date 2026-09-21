package com.inventory.product.service.vertical;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.documentservice.domain.KotStamp;
import com.inventory.documentservice.rest.dto.GenerateKotRequest;
import com.inventory.documentservice.rest.dto.KotItem;
import com.inventory.documentservice.service.KotPdfService;
import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.kot.CafeKotPunchPort;
import com.inventory.pluginengine.kot.CafeKotTicket;
import com.inventory.pluginengine.kot.CafeKotTicketLine;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Cafe cart-punch orchestration: turns a reconciled cart into kitchen tickets and renders one
 * ticket's document.
 *
 * <p>This service creates and returns tickets. It never prints — the frontend fetches the
 * document and does that.
 *
 * <p>The punch entities live in the cafe plugin and are reached through {@link CafeKotPunchPort},
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

  private CafeKotPunchPort port() {
    return pluginRegistry
        .require(VERTICAL_ID)
        .getCafeKotPunchPort()
        .orElseThrow(
            () -> new ValidationException("This shop's vertical does not support kitchen tickets"));
  }

  /** Punches the cart into kitchen tickets, or replays what an earlier attempt already created. */
  public List<CafeKotTicket> punch(
      String shopId, String userId, String purchaseId, String idempotencyKey) {
    if (!StringUtils.hasText(idempotencyKey)) {
      throw new ValidationException("Idempotency-Key is required when punching a cart");
    }
    return port().punch(shopId, userId, purchaseId, idempotencyKey);
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
   * A ticket cancels if its {@code kind} says so — or, for a ticket written before {@code kind}
   * existed (the retired running-order path, which left {@code kind == null}), if its {@code
   * status} says VOIDED. Either signal alone is enough: a legacy voided ticket must never render
   * unstamped.
   */
  private static boolean isCancelled(CafeKotTicket ticket) {
    return "CANCEL".equals(ticket.getKind()) || "VOIDED".equals(ticket.getStatus());
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
