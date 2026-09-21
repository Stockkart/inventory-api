package com.inventory.plugins.cafe;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.integration.ShopMenuLookup;
import com.inventory.pluginengine.menu.MenuDepartments;
import com.inventory.pluginengine.menu.MenuItem;
import com.inventory.pluginengine.order.KotView;
import com.inventory.pluginengine.order.PunchCommand;
import com.inventory.pluginengine.order.PunchLine;
import com.inventory.pluginengine.order.RunningOrderStore;
import com.inventory.pluginengine.order.RunningOrderView;
import com.inventory.pluginengine.order.VoidResult;
import com.inventory.pluginengine.ref.SellableRef;
import com.inventory.plugins.cafe.domain.CafeKot;
import com.inventory.plugins.cafe.domain.CafeKotLine;
import com.inventory.plugins.cafe.domain.CafeKotStatus;
import com.inventory.plugins.cafe.domain.CafeLineStatus;
import com.inventory.plugins.cafe.domain.CafeOrder;
import com.inventory.plugins.cafe.domain.CafeOrderLine;
import com.inventory.plugins.cafe.domain.CafeOrderPunch;
import com.inventory.plugins.cafe.domain.CafeOrderStatus;
import com.inventory.plugins.cafe.domain.CafeOrderType;
import com.inventory.plugins.cafe.domain.CafePunchStatus;
import com.inventory.plugins.cafe.domain.CafeKotRepository;
import com.inventory.plugins.cafe.domain.CafeOrderPunchRepository;
import com.inventory.plugins.cafe.domain.CafeOrderRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Cafe running orders and kitchen tickets.
 *
 * <p>There are no MongoDB transactions in this codebase, so nothing here may rely on multi-document
 * atomicity. Punching is made safe by ordering instead: the punch claim is written before any
 * ticket exists.
 */
@Component
@Slf4j
public class CafeRunningOrderStore implements RunningOrderStore {

  private final CafeOrderRepository orderRepository;
  private final CafeKotRepository kotRepository;
  private final CafeOrderPunchRepository punchRepository;
  private final CafeSequenceService sequenceService;
  private final ShopMenuLookup menuLookup;

  public CafeRunningOrderStore(
      CafeOrderRepository orderRepository,
      CafeKotRepository kotRepository,
      CafeOrderPunchRepository punchRepository,
      CafeSequenceService sequenceService,
      ShopMenuLookup menuLookup) {
    this.orderRepository = orderRepository;
    this.kotRepository = kotRepository;
    this.punchRepository = punchRepository;
    this.sequenceService = sequenceService;
    this.menuLookup = menuLookup;
  }

  @Override
  public String getVerticalId() {
    return "cafe";
  }

  @Override
  public RunningOrderView openOrder(
      String shopId, String userId, String orderType, String tableLabel, String tokenNo) {
    CafeOrderType type;
    try {
      type = CafeOrderType.valueOf(String.valueOf(orderType).trim().toUpperCase());
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new ValidationException("orderType must be DINE_IN or TAKEAWAY");
    }
    if (type == CafeOrderType.DINE_IN && !StringUtils.hasText(tableLabel)) {
      throw new ValidationException("tableLabel is required for a DINE_IN order");
    }
    LocalDate businessDate = LocalDate.now();

    CafeOrder order = new CafeOrder();
    order.setShopId(shopId);
    order.setOrderNo(sequenceService.allocate(shopId, businessDate, CafeSequenceSeries.ORDER));
    order.setOrderType(type);
    order.setTableLabel(type == CafeOrderType.DINE_IN ? tableLabel.trim() : null);
    order.setTokenNo(type == CafeOrderType.TAKEAWAY ? tokenNo : null);
    order.setStatus(CafeOrderStatus.OPEN);
    order.setBusinessDate(businessDate.toString());
    order.setCreatedAt(Instant.now());
    order.setCreatedBy(userId);
    return CafeOrderMapper.toView(orderRepository.save(order));
  }

  @Override
  public Optional<RunningOrderView> findOrder(String shopId, String orderId) {
    return orderRepository
        .findByIdAndShopId(orderId, shopId)
        .map(o -> CafeOrderMapper.toView(o, (int) punchRepository.countByShopIdAndOrderId(shopId, o.getId())));
  }

  @Override
  public List<RunningOrderView> listOpenOrders(String shopId) {
    return orderRepository
        .findByShopIdAndStatusOrderByOrderNoDesc(shopId, CafeOrderStatus.OPEN)
        .stream()
        .map(o -> CafeOrderMapper.toView(o, (int) punchRepository.countByShopIdAndOrderId(shopId, o.getId())))
        .toList();
  }

  @Override
  public List<KotView> punch(PunchCommand command) {
    String shopId = command.getShopId();
    String key = command.getIdempotencyKey();
    if (!StringUtils.hasText(key)) {
      throw new ValidationException("Idempotency-Key is required when punching a round");
    }
    if (command.getLines() == null || command.getLines().isEmpty()) {
      throw new ValidationException("At least one line is required");
    }

    Optional<CafeOrderPunch> existing = punchRepository.findByShopIdAndIdempotencyKey(shopId, key);
    if (existing.isPresent() && existing.get().getStatus() == CafePunchStatus.COMPLETE) {
      List<CafeKot> done = kotRepository.findByShopIdAndPunchId(shopId, existing.get().getId());
      log.info("Replayed punch {} on shop {} — returning {} existing KOTs", key, shopId, done.size());
      return CafeOrderMapper.toKotViews(done);
    }

    CafeOrder order = requireOpenOrder(shopId, command.getOrderId());

    CafeOrderPunch punch;
    if (existing.isPresent()) {
      // CLAIMED: a previous attempt died between claiming and finishing. Discard whatever tickets
      // it managed to write and redrive — nothing outside this punch refers to them yet. The round
      // number is kept, so the kitchen's numbering does not shift under a retry.
      punch = existing.get();
      log.warn("Resuming claimed punch {} on shop {} — discarding partial tickets", key, shopId);
      kotRepository.deleteByShopIdAndPunchId(shopId, punch.getId());
    } else {
      CafeOrderPunch claim = new CafeOrderPunch();
      claim.setShopId(shopId);
      claim.setOrderId(order.getId());
      claim.setIdempotencyKey(key);
      claim.setRoundNo((int) punchRepository.countByShopIdAndOrderId(shopId, order.getId()) + 1);
      claim.setStatus(CafePunchStatus.CLAIMED);
      claim.setCreatedAt(Instant.now());
      claim.setCreatedBy(command.getUserId());
      try {
        // This insert IS the claim, and it must land before any ticket exists.
        punch = punchRepository.save(claim);
      } catch (DuplicateKeyException e) {
        CafeOrderPunch winner =
            punchRepository
                .findByShopIdAndIdempotencyKey(shopId, key)
                .orElseThrow(() -> new ValidationException("Punch " + key + " is in flight"));
        List<CafeKot> theirs = kotRepository.findByShopIdAndPunchId(shopId, winner.getId());
        log.info("Concurrent punch {} on shop {} — returning the winner's tickets", key, shopId);
        return CafeOrderMapper.toKotViews(theirs);
      }
    }

    Map<String, List<CafeOrderLine>> byDepartment = new LinkedHashMap<>();
    for (PunchLine input : command.getLines()) {
      CafeOrderLine line = buildLine(shopId, input);
      byDepartment.computeIfAbsent(line.getDepartment(), d -> new ArrayList<>()).add(line);
    }

    LocalDate businessDate = LocalDate.parse(order.getBusinessDate());
    int roundNo = punch.getRoundNo();

    List<CafeKot> kots = new ArrayList<>();
    for (Map.Entry<String, List<CafeOrderLine>> entry : byDepartment.entrySet()) {
      CafeKot kot = new CafeKot();
      kot.setShopId(shopId);
      kot.setOrderId(order.getId());
      kot.setKotNo(sequenceService.allocate(shopId, businessDate, CafeSequenceSeries.KOT));
      kot.setDepartment(entry.getKey());
      kot.setRoundNo(roundNo);
      kot.setStatus(CafeKotStatus.ISSUED);
      kot.setPunchId(punch.getId());
      kot.setBusinessDate(order.getBusinessDate());
      kot.setCreatedAt(Instant.now());
      kot.setCreatedBy(command.getUserId());
      kot.setLines(entry.getValue().stream().map(CafeRunningOrderStore::snapshot).toList());
      kots.add(kot);
    }

    List<CafeKot> saved = kotRepository.saveAll(kots);

    int index = 0;
    for (Map.Entry<String, List<CafeOrderLine>> entry : byDepartment.entrySet()) {
      String kotId = saved.get(index++).getId();
      for (CafeOrderLine line : entry.getValue()) {
        line.setKotId(kotId);
        order.getLines().add(line);
      }
    }
    order.setUpdatedAt(Instant.now());
    order.setUpdatedBy(command.getUserId());
    orderRepository.save(order);

    punch.setKotIds(saved.stream().map(CafeKot::getId).toList());
    punch.setStatus(CafePunchStatus.COMPLETE);
    punchRepository.save(punch);

    return CafeOrderMapper.toKotViews(saved);
  }

  @Override
  public Optional<KotView> findKot(String shopId, String kotId) {
    return kotRepository.findByIdAndShopId(kotId, shopId).map(CafeOrderMapper::toView);
  }

  @Override
  public VoidResult voidLines(
      String shopId, String userId, String kotId, List<String> lineIds, String reason) {
    if (lineIds == null || lineIds.isEmpty()) {
      throw new ValidationException("At least one lineId is required");
    }
    if (!StringUtils.hasText(reason)) {
      throw new ValidationException("A void reason is required");
    }
    CafeKot kot =
        kotRepository
            .findByIdAndShopId(kotId, shopId)
            .orElseThrow(() -> new ResourceNotFoundException("CafeKot", "id", kotId));
    if (kot.getStatus() == CafeKotStatus.VOIDED) {
      throw new ValidationException("KOT is already voided");
    }
    CafeOrder order = requireOpenOrder(shopId, kot.getOrderId());

    // Resolve every line before mutating any of them: without transactions, a half-applied void
    // would leave the order disagreeing with the slip the kitchen was handed.
    List<CafeOrderLine> targets = new ArrayList<>();
    for (String lineId : lineIds) {
      CafeOrderLine line =
          order.getLines().stream()
              .filter(l -> lineId.equals(l.getLineId()) && kotId.equals(l.getKotId()))
              .findFirst()
              .orElseThrow(
                  () -> new ValidationException("Line " + lineId + " does not belong to " + kotId));
      if (line.getStatus() != CafeLineStatus.ACTIVE) {
        throw new ValidationException("Line " + lineId + " is already voided");
      }
      targets.add(line);
    }

    String batchId = UUID.randomUUID().toString();
    Instant now = Instant.now();
    for (CafeOrderLine line : targets) {
      line.setStatus(CafeLineStatus.VOIDED);
      line.setVoidBatchId(batchId);
      line.setVoidReason(reason.trim());
      line.setVoidedBy(userId);
      line.setVoidedAt(now);
    }
    order.setUpdatedAt(now);
    order.setUpdatedBy(userId);
    orderRepository.save(order);

    boolean allVoided =
        order.getLines().stream()
            .filter(l -> kotId.equals(l.getKotId()))
            .allMatch(l -> l.getStatus() == CafeLineStatus.VOIDED);
    kot.setVoidReason(reason.trim());
    kot.setVoidedBy(userId);
    kot.setVoidedAt(now);
    if (allVoided) {
      kot.setStatus(CafeKotStatus.VOIDED);
    }
    CafeKot savedKot = kotRepository.save(kot);

    return VoidResult.builder()
        .kot(CafeOrderMapper.toView(savedKot))
        .voidBatchId(batchId)
        .reason(reason.trim())
        .voidedLines(CafeOrderMapper.toKotLineViews(savedKot, targets))
        .ticketFullyVoided(allVoided)
        .build();
  }

  @Override
  public Optional<VoidResult> findVoidBatch(String shopId, String kotId, String voidBatchId) {
    CafeKot kot = kotRepository.findByIdAndShopId(kotId, shopId).orElse(null);
    if (kot == null) {
      return Optional.empty();
    }
    CafeOrder order = orderRepository.findByIdAndShopId(kot.getOrderId(), shopId).orElse(null);
    if (order == null) {
      return Optional.empty();
    }
    List<CafeOrderLine> inBatch =
        order.getLines().stream()
            .filter(l -> voidBatchId.equals(l.getVoidBatchId()) && kotId.equals(l.getKotId()))
            .toList();
    if (inBatch.isEmpty()) {
      return Optional.empty();
    }
    boolean allVoided =
        order.getLines().stream()
            .filter(l -> kotId.equals(l.getKotId()))
            .allMatch(l -> l.getStatus() == CafeLineStatus.VOIDED);
    return Optional.of(
        VoidResult.builder()
            .kot(CafeOrderMapper.toView(kot))
            .voidBatchId(voidBatchId)
            .reason(inBatch.get(0).getVoidReason())
            .voidedLines(CafeOrderMapper.toKotLineViews(kot, inBatch))
            .ticketFullyVoided(allVoided)
            .build());
  }

  @Override
  public KotView markReprinted(String shopId, String kotId) {
    CafeKot kot =
        kotRepository
            .findByIdAndShopId(kotId, shopId)
            .orElseThrow(() -> new ResourceNotFoundException("CafeKot", "id", kotId));
    if (kot.getStatus() == CafeKotStatus.VOIDED) {
      throw new ValidationException("A voided KOT cannot be reprinted");
    }
    kot.setReprintCount(kot.getReprintCount() == null ? 1 : kot.getReprintCount() + 1);
    return CafeOrderMapper.toView(kotRepository.save(kot));
  }

  @Override
  public RunningOrderView bindPurchase(String shopId, String orderId, String purchaseId) {
    if (!StringUtils.hasText(purchaseId)) {
      throw new ValidationException("purchaseId is required");
    }
    CafeOrder order = requireOpenOrder(shopId, orderId);
    if (StringUtils.hasText(order.getPurchaseId())
        && !order.getPurchaseId().equals(purchaseId)) {
      throw new ValidationException(
          "Order is already bound to purchase " + order.getPurchaseId());
    }
    order.setPurchaseId(purchaseId);
    order.setUpdatedAt(Instant.now());
    return CafeOrderMapper.toView(orderRepository.save(order));
  }

  @Override
  public RunningOrderView markBilled(String shopId, String userId, String orderId) {
    CafeOrder order = requireOpenOrder(shopId, orderId);
    order.setStatus(CafeOrderStatus.BILLED);
    order.setUpdatedAt(Instant.now());
    order.setUpdatedBy(userId);
    return CafeOrderMapper.toView(orderRepository.save(order));
  }

  @Override
  public RunningOrderView cancelOrder(
      String shopId, String userId, String orderId, String reason) {
    if (!StringUtils.hasText(reason)) {
      throw new ValidationException("A cancellation reason is required");
    }
    CafeOrder order = requireOpenOrder(shopId, orderId);

    order.getLines().stream()
        .filter(l -> l.getStatus() == CafeLineStatus.ACTIVE)
        .forEach(l -> l.setStatus(CafeLineStatus.VOIDED));
    order.setStatus(CafeOrderStatus.CANCELLED);
    order.setCancelReason(reason.trim());
    order.setUpdatedAt(Instant.now());
    order.setUpdatedBy(userId);
    orderRepository.save(order);

    for (CafeKot kot : kotRepository.findByShopIdAndOrderId(shopId, orderId)) {
      // A ticket the kitchen was already told to drop does not get a second slip.
      if (kot.getStatus() == CafeKotStatus.VOIDED) {
        continue;
      }
      kot.setStatus(CafeKotStatus.VOIDED);
      kot.setVoidReason(reason.trim());
      kot.setVoidedBy(userId);
      kot.setVoidedAt(Instant.now());
      kotRepository.save(kot);
    }
    return CafeOrderMapper.toView(order);
  }

  private CafeOrder requireOpenOrder(String shopId, String orderId) {
    CafeOrder order =
        orderRepository
            .findByIdAndShopId(orderId, shopId)
            .orElseThrow(() -> new ResourceNotFoundException("CafeOrder", "id", orderId));
    if (order.getStatus() != CafeOrderStatus.OPEN) {
      throw new ValidationException("Order is " + order.getStatus() + " and cannot be changed");
    }
    return order;
  }

  private CafeOrderLine buildLine(String shopId, PunchLine input) {
    if (input.getQuantity() == null || input.getQuantity() < 1) {
      throw new ValidationException("Quantity must be at least 1 for " + input.getSellableRef());
    }
    SellableRef ref;
    try {
      ref = SellableRef.parse(input.getSellableRef());
    } catch (IllegalArgumentException e) {
      // A malformed ref is bad client input, not a server fault.
      throw new ValidationException("Invalid sellableRef: " + input.getSellableRef());
    }

    String name;
    String department;
    if (ref.isMenu()) {
      MenuItem item =
          menuLookup
              .findMenuItem(shopId, ref.id())
              .orElseThrow(
                  () -> new ResourceNotFoundException("MenuItem", "sellableRef", ref.encode()));
      if (!Boolean.TRUE.equals(item.getAvailable())) {
        throw new ValidationException("Menu item is not available: " + item.getName());
      }
      name = item.getName();
      department = MenuDepartments.resolve(item.getDepartment());
    } else {
      name = ref.id();
      department = MenuDepartments.DEFAULT;
    }

    CafeOrderLine line = new CafeOrderLine();
    line.setLineId(UUID.randomUUID().toString());
    line.setSellableRef(ref.encode());
    line.setName(name);
    line.setQuantity(input.getQuantity());
    line.setNote(StringUtils.hasText(input.getNote()) ? input.getNote().trim() : null);
    line.setDepartment(department);
    line.setStatus(CafeLineStatus.ACTIVE);
    return line;
  }

  private static CafeKotLine snapshot(CafeOrderLine line) {
    CafeKotLine copy = new CafeKotLine();
    copy.setLineId(line.getLineId());
    copy.setName(line.getName());
    copy.setQuantity(line.getQuantity());
    copy.setNote(line.getNote());
    return copy;
  }
}
