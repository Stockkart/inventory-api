package com.inventory.plan.service.voucher;

import com.inventory.common.tx.MongoTx;
import com.inventory.plan.domain.model.AddOnVoucher;
import com.inventory.plan.domain.model.OrderLine;
import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.PricedCart;
import com.inventory.plan.domain.model.VoucherRedemption;
import com.inventory.plan.domain.model.VoucherRedemptionStatus;
import com.inventory.plan.domain.model.VoucherRejection;
import com.inventory.plan.domain.repository.AddOnVoucherRepository;
import com.inventory.plan.domain.repository.VoucherRedemptionRepository;
import com.inventory.plan.exception.VoucherRejectedException;
import com.inventory.plan.service.order.OrderReservationHandler;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.ArithmeticOperators;
import org.springframework.data.mongodb.core.aggregation.ComparisonOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Takes a voucher slot when checkout creates the order, so a capped voucher can never be charged at
 * its discounted price and then found exhausted after payment (§27.3).
 */
/** Runs before the wallet handler ({@code @Order(20)}): vouchers are priced before wallet credit. */
@Component
@Order(10)
@Slf4j
public class VoucherReservationHandler implements OrderReservationHandler {

  /** MongoTx flow name: a redemption row's status and its voucher's counters change together. */
  static final String SLOT_FLOW = "voucher_slot";

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private AddOnVoucherRepository voucherRepository;

  @Autowired
  private VoucherRedemptionRepository redemptionRepository;

  @Autowired
  private MongoTx mongoTx;

  Clock clock = Clock.systemUTC();

  /** All of the order's vouchers or none: a failure releases the slots this call already took. */
  @Override
  public void reserve(PlanPaymentOrder order, PricedCart cart) {
    try {
      for (OrderLine line : voucherLines(order.getItems())) {
        holdSlot(order, line);
      }
    } catch (RuntimeException e) {
      release(order);
      throw e;
    }
  }

  @Override
  public void release(PlanPaymentOrder order) {
    for (VoucherRedemption redemption : redemptionRepository.findByOrderId(order.getId())) {
      releaseSlot(redemption);
    }
  }

  @Override
  public boolean reacquire(PlanPaymentOrder order) {
    try {
      reserve(order, null);
      return true;
    } catch (VoucherRejectedException e) {
      log.warn("Order {} could not take back voucher {}: {}", order.getId(),
          e.getDetails().get("voucherCode"), e.getReason());
      return false;
    }
  }

  /** RESERVED → REDEEMED, moving the slot from reserved to used. Safe to repeat. */
  public void redeem(PlanPaymentOrder order) {
    for (VoucherRedemption redemption : redemptionRepository.findByOrderId(order.getId())) {
      if (redemption.getStatus() == VoucherRedemptionStatus.REDEEMED) {
        continue;
      }
      // status and counters in one transaction: a crash between them would leave the slot counted twice
      mongoTx.run(SLOT_FLOW, order.getShopId(), () -> {
        VoucherRedemption redeemed = mongoTemplate.findAndModify(
            new Query(Criteria.where("_id").is(redemption.getId()).and("status").is(VoucherRedemptionStatus.RESERVED)),
            new Update().set("status", VoucherRedemptionStatus.REDEEMED).set("redeemedAt", clock.instant()),
            FindAndModifyOptions.options().returnNew(true), VoucherRedemption.class);
        if (redeemed == null) {
          throw new IllegalStateException("Voucher " + redemption.getVoucherCode() + " for order "
              + order.getId() + " is " + redemption.getStatus() + ", not reserved");
        }
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(redemption.getVoucherId())),
            new Update().inc("reservedCount", -1).inc("redemptionCount", 1), AddOnVoucher.class);
        return null;
      });
    }
  }

  private void holdSlot(PlanPaymentOrder order, OrderLine line) {
    AddOnVoucher voucher = voucherRepository.findByCode(line.getVoucherCode())
        .orElseThrow(() -> new VoucherRejectedException(line.getVoucherCode(), VoucherRejection.NOT_FOUND,
            "Voucher " + line.getVoucherCode() + " does not exist"));
    Optional<VoucherRedemption> existing = redemptionRepository.findByVoucherIdAndOrderId(voucher.getId(), order.getId());
    if (existing.isPresent() && existing.get().getStatus() != VoucherRedemptionStatus.RELEASED) {
      return;
    }
    VoucherRedemption redemption = existing.map(released -> relock(released, voucher))
        .orElseGet(() -> insertReservation(order, line, voucher));
    if (!takeCapacity(voucher)) {
      releaseRow(redemption.getId());
      throw new VoucherRejectedException(voucher.getCode(), VoucherRejection.EXHAUSTED,
          "Voucher " + voucher.getCode() + " has been fully used");
    }
  }

  private VoucherRedemption insertReservation(PlanPaymentOrder order, OrderLine line, AddOnVoucher voucher) {
    VoucherRedemption redemption = VoucherRedemption.builder()
        .voucherId(voucher.getId())
        .voucherCode(voucher.getCode())
        .shopId(order.getShopId())
        .orderId(order.getId())
        .addOnCode(line.getCode())
        .discount(line.getDiscount())
        .status(VoucherRedemptionStatus.RESERVED)
        .holdsSlot(true)
        .singleUsePerShop(voucher.isSingleUsePerShop())
        .reservedAt(clock.instant())
        .build();
    try {
      return mongoTemplate.insert(redemption);
    } catch (DuplicateKeyException e) {
      throw alreadyUsed(voucher);
    }
  }

  private VoucherRedemption relock(VoucherRedemption released, AddOnVoucher voucher) {
    try {
      VoucherRedemption relocked = mongoTemplate.findAndModify(
          new Query(Criteria.where("_id").is(released.getId()).and("status").is(VoucherRedemptionStatus.RELEASED)),
          new Update().set("status", VoucherRedemptionStatus.RESERVED).set("holdsSlot", true)
              .set("reservedAt", clock.instant()).unset("releasedAt"),
          FindAndModifyOptions.options().returnNew(true), VoucherRedemption.class);
      if (relocked == null) {
        throw new IllegalStateException("Voucher redemption " + released.getId() + " changed while reacquiring");
      }
      return relocked;
    } catch (DuplicateKeyException e) {
      throw alreadyUsed(voucher);
    }
  }

  /** {@code reservedCount + redemptionCount < maxRedemptions} → reservedCount + 1, in one write. */
  private boolean takeCapacity(AddOnVoucher voucher) {
    Criteria criteria = Criteria.where("_id").is(voucher.getId());
    if (voucher.getMaxRedemptions() != null) {
      criteria = criteria.andOperator(Criteria.expr(ComparisonOperators.Lt.valueOf(
          ArithmeticOperators.Add.valueOf("reservedCount").add("redemptionCount")).lessThan("maxRedemptions")));
    }
    return mongoTemplate.updateFirst(new Query(criteria), new Update().inc("reservedCount", 1), AddOnVoucher.class)
        .getModifiedCount() == 1;
  }

  /** RESERVED → RELEASED and the voucher's reservedCount − 1, in one transaction. */
  private void releaseSlot(VoucherRedemption redemption) {
    mongoTx.run(SLOT_FLOW, redemption.getShopId(), () -> {
      if (releaseRow(redemption.getId())) {
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(redemption.getVoucherId())),
            new Update().inc("reservedCount", -1), AddOnVoucher.class);
      }
      return null;
    });
  }

  /** RESERVED → RELEASED, freeing the per-shop slot. Returns whether this call released it. */
  private boolean releaseRow(String redemptionId) {
    return mongoTemplate.updateFirst(
        new Query(Criteria.where("_id").is(redemptionId).and("status").is(VoucherRedemptionStatus.RESERVED)),
        new Update().set("status", VoucherRedemptionStatus.RELEASED).unset("holdsSlot").set("releasedAt", clock.instant()),
        VoucherRedemption.class).getModifiedCount() == 1;
  }

  private static VoucherRejectedException alreadyUsed(AddOnVoucher voucher) {
    return new VoucherRejectedException(voucher.getCode(), VoucherRejection.ALREADY_REDEEMED,
        "This shop has already used voucher " + voucher.getCode());
  }

  private static List<OrderLine> voucherLines(List<OrderLine> items) {
    return items == null ? List.of() : items.stream().filter(line -> line.getVoucherCode() != null).toList();
  }
}
