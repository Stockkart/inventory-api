package com.inventory.product.service.vertical;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.documentservice.domain.KotStamp;
import com.inventory.documentservice.rest.dto.GenerateKotRequest;
import com.inventory.documentservice.rest.dto.KotItem;
import com.inventory.documentservice.service.KotPdfService;
import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.order.KotView;
import com.inventory.pluginengine.order.PunchCommand;
import com.inventory.pluginengine.order.PunchLine;
import com.inventory.pluginengine.order.RunningOrderLineView;
import com.inventory.pluginengine.order.RunningOrderStore;
import com.inventory.pluginengine.order.RunningOrderView;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.rest.dto.request.AddToCartRequest;
import com.inventory.product.rest.dto.request.UpdatePurchaseStatusRequest;
import com.inventory.product.rest.dto.response.AddToCartResponse;
import com.inventory.product.service.CheckoutService;
import com.inventory.user.service.RbacService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Cafe running-order orchestration.
 *
 * <p>Entities live in the cafe plugin and are reached through {@link RunningOrderStore}. This class
 * adds the three things a plugin cannot see: RBAC, PDF rendering and checkout.
 *
 * <p>There are no MongoDB transactions in this codebase, so {@link #settle} is made safe by
 * ordering: the cart id is recorded the moment the cart exists, before the purchase is completed.
 */
@Service
@Slf4j
public class CafeOrderService {

  private static final String VERTICAL_ID = "cafe";
  private static final DateTimeFormatter PRINTED_AT = DateTimeFormatter.ofPattern("dd/MM HH:mm");

  private final PluginRegistry pluginRegistry;
  private final CheckoutService checkoutService;
  private final KotPdfService kotPdfService;
  private final RbacService rbacService;

  public CafeOrderService(
      PluginRegistry pluginRegistry,
      CheckoutService checkoutService,
      KotPdfService kotPdfService,
      RbacService rbacService) {
    this.pluginRegistry = pluginRegistry;
    this.checkoutService = checkoutService;
    this.kotPdfService = kotPdfService;
    this.rbacService = rbacService;
  }

  private RunningOrderStore store() {
    return pluginRegistry
        .require(VERTICAL_ID)
        .getRunningOrderStore()
        .orElseThrow(
            () -> new ValidationException("This shop's vertical does not support running orders"));
  }

  public RunningOrderView openOrder(
      String shopId, String userId, String orderType, String tableLabel, String tokenNo) {
    return store().openOrder(shopId, userId, orderType, tableLabel, tokenNo);
  }

  public List<RunningOrderView> listOpenOrders(String shopId) {
    return store().listOpenOrders(shopId);
  }

  public RunningOrderView getOrder(String shopId, String orderId) {
    return store()
        .findOrder(shopId, orderId)
        .orElseThrow(() -> new ResourceNotFoundException("CafeOrder", "id", orderId));
  }

  public List<KotView> punch(
      String shopId, String userId, String orderId, String idempotencyKey, List<PunchLine> lines) {
    return store()
        .punch(
            PunchCommand.builder()
                .shopId(shopId)
                .userId(userId)
                .orderId(orderId)
                .idempotencyKey(idempotencyKey)
                .lines(lines)
                .build());
  }

  /** The stamp is derived from state: a voided ticket always renders as a cancellation slip. */
  public byte[] kotDocument(String shopId, String kotId) {
    KotView kot =
        store()
            .findKot(shopId, kotId)
            .orElseThrow(() -> new ResourceNotFoundException("CafeKot", "id", kotId));
    KotStamp stamp = "VOIDED".equals(kot.getStatus()) ? KotStamp.CANCELLED : KotStamp.NONE;
    return kotPdfService.generateKotPdf(toDocumentRequest(shopId, kot, stamp));
  }

  public byte[] reprint(String shopId, String kotId) {
    KotView kot = store().markReprinted(shopId, kotId);
    return kotPdfService.generateKotPdf(toDocumentRequest(shopId, kot, KotStamp.REPRINT));
  }

  public KotView voidLines(
      String shopId, String userId, String kotId, List<String> lineIds, String reason) {
    rbacService.requireModule(userId, shopId, RbacService.MODULE_KOT_VOID);
    return store().voidLines(shopId, userId, kotId, lineIds, reason);
  }

  public RunningOrderView cancel(String shopId, String userId, String orderId, String reason) {
    rbacService.requireModule(userId, shopId, RbacService.MODULE_KOT_VOID);
    return store().cancelOrder(shopId, userId, orderId, reason);
  }

  /**
   * Settle in three steps, each safe to repeat.
   *
   * <ol>
   *   <li>Gate on status. BILLED returns what already exists; CANCELLED is rejected.
   *   <li>Create the cart only if there is no purchaseId yet, and record it immediately. The
   *       existing checkout is already two-phase, so the PENDING purchase's id is the idempotency
   *       handle — no separate one is needed.
   *   <li>Complete the purchase and mark the order billed.
   * </ol>
   *
   * <p>A crash between steps 2 and 3 leaves an OPEN order holding a PENDING purchaseId; the retry
   * skips step 2 and completes the purchase that already exists. No second purchase is reachable.
   */
  public RunningOrderView settle(
      String shopId,
      String userId,
      String orderId,
      String businessType,
      String paymentMethod,
      HttpServletRequest httpRequest) {
    RunningOrderView order = getOrder(shopId, orderId);

    if ("BILLED".equals(order.getStatus())) {
      log.info("Order {} is already billed as purchase {}", orderId, order.getPurchaseId());
      return order;
    }
    if (!"OPEN".equals(order.getStatus())) {
      throw new ValidationException("Order is " + order.getStatus() + " and cannot be settled");
    }

    String purchaseId = order.getPurchaseId();
    if (!StringUtils.hasText(purchaseId)) {
      AddToCartRequest cart = new AddToCartRequest();
      cart.setBusinessType(businessType);
      cart.setCreateNewQuotation(Boolean.TRUE);
      cart.setItems(toCartItems(order));
      AddToCartResponse response = checkoutService.addToCart(cart, httpRequest);
      purchaseId = response.getPurchaseId();
      // Recorded before completion, never after: this is what makes a retry safe.
      store().bindPurchase(shopId, orderId, purchaseId);
    } else {
      log.info("Resuming settlement of order {} against existing cart {}", orderId, purchaseId);
    }

    // Checkout is three-phase, not two: CheckoutValidator allows only
    // CREATED -> PENDING -> COMPLETED. Drive the purchase from wherever it actually is, so a
    // retry that finds it already PENDING (or COMPLETED) does not attempt an illegal transition.
    PurchaseStatus current = checkoutService.getCart(httpRequest, purchaseId).getStatus();
    if (current != PurchaseStatus.COMPLETED) {
      if (current == PurchaseStatus.CREATED) {
        advance(purchaseId, PurchaseStatus.PENDING, paymentMethod, httpRequest);
      }
      advance(purchaseId, PurchaseStatus.COMPLETED, paymentMethod, httpRequest);
    } else {
      log.info("Purchase {} is already COMPLETED; only marking order {} billed", purchaseId, orderId);
    }

    return store().markBilled(shopId, userId, orderId);
  }

  private void advance(
      String purchaseId,
      PurchaseStatus status,
      String paymentMethod,
      HttpServletRequest httpRequest) {
    UpdatePurchaseStatusRequest request = new UpdatePurchaseStatusRequest();
    request.setPurchaseId(purchaseId);
    request.setStatus(status);
    request.setPaymentMethod(paymentMethod);
    checkoutService.updatePurchaseStatus(request, httpRequest);
  }

  private List<AddToCartRequest.CartItem> toCartItems(RunningOrderView order) {
    List<AddToCartRequest.CartItem> items = new ArrayList<>();
    for (RunningOrderLineView line : order.getLines()) {
      if (!"ACTIVE".equals(line.getStatus())) {
        continue;
      }
      AddToCartRequest.CartItem item = new AddToCartRequest.CartItem();
      item.setSellableRef(line.getSellableRef());
      item.setQuantity(line.getQuantity());
      items.add(item);
    }
    if (items.isEmpty()) {
      throw new ValidationException("Order has no active lines to settle");
    }
    return items;
  }

  private GenerateKotRequest toDocumentRequest(String shopId, KotView kot, KotStamp stamp) {
    RunningOrderView order = getOrder(shopId, kot.getOrderId());
    GenerateKotRequest request = new GenerateKotRequest();
    request.setKotNo(kot.getKotNo());
    request.setOrderNo(order.getOrderNo());
    request.setOrderType(order.getOrderType());
    request.setTableLabel(order.getTableLabel());
    request.setTokenNo(order.getTokenNo());
    request.setDepartment(kot.getDepartment());
    request.setRoundNo(kot.getRoundNo());
    request.setPrintedAt(LocalDateTime.now().format(PRINTED_AT));
    request.setStamp(stamp);
    request.setVoidReason(stamp == KotStamp.CANCELLED ? kot.getVoidReason() : null);
    request.setItems(
        kot.getLines().stream()
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
