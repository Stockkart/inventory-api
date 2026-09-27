package com.inventory.plan.migration;

import com.inventory.plan.domain.model.AddOnVoucher;
import com.inventory.plan.domain.model.VoucherRedemption;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/** Voucher indexes (§27.3). Auto index creation is off, so they are ensured on startup. */
@Component
public class VoucherIndexes {

  @Autowired
  private MongoTemplate mongoTemplate;

  @EventListener(ApplicationReadyEvent.class)
  @Order(22)
  public void ensureIndexes() {
    var vouchers = mongoTemplate.indexOps(AddOnVoucher.class);
    vouchers.ensureIndex(new Index().on("code", Sort.Direction.ASC).unique().named("code_unique"));
    vouchers.ensureIndex(new Index().on("addOnCode", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC)
        .named("addOnCode_createdAt"));

    var redemptions = mongoTemplate.indexOps(VoucherRedemption.class);
    redemptions.ensureIndex(new Index()
        .on("voucherId", Sort.Direction.ASC)
        .on("orderId", Sort.Direction.ASC)
        .unique()
        .named("voucher_order_unique"));
    redemptions.ensureIndex(new Index()
        .on("voucherCode", Sort.Direction.ASC)
        .on("shopId", Sort.Direction.ASC)
        .unique()
        .partial(PartialIndexFilter.of(Criteria.where("holdsSlot").is(true).and("singleUsePerShop").is(true)))
        .named("single_use_per_shop"));
    redemptions.ensureIndex(new Index().on("orderId", Sort.Direction.ASC).named("orderId"));
  }
}
