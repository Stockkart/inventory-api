package com.inventory.plan.migration;

import com.inventory.plan.domain.model.AddOn;
import com.inventory.plan.domain.model.ShopAddOn;
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

/**
 * Add-on indexes. Auto index creation is off, so they are ensured on startup. Add-on rows
 * themselves are managed from the admin app.
 */
@Component
public class AddOnIndexes {

  @Autowired
  private MongoTemplate mongoTemplate;

  @EventListener(ApplicationReadyEvent.class)
  @Order(21)
  public void ensureIndexes() {
    mongoTemplate.indexOps(AddOn.class)
        .ensureIndex(new Index().on("code", Sort.Direction.ASC).unique().named("code_unique"));
    var shopAddOns = mongoTemplate.indexOps(ShopAddOn.class);
    shopAddOns.ensureIndex(new Index()
        .on("sourceOrderId", Sort.Direction.ASC)
        .on("addOnCode", Sort.Direction.ASC)
        .unique()
        .partial(PartialIndexFilter.of(Criteria.where("sourceOrderId").exists(true)))
        .named("source_order_addon_unique"));
    shopAddOns.ensureIndex(new Index()
        .on("shopId", Sort.Direction.ASC)
        .on("grantType", Sort.Direction.ASC)
        .named("shop_grantType"));
  }
}
