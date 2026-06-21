package com.inventory.plugins.cafe;

import com.inventory.pluginengine.cart.CheckoutCompletionContext;
import com.inventory.pluginengine.cart.CheckoutCompletionHandler;
import com.inventory.pluginengine.cart.CheckoutCompletionResult;
import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class CafeCheckoutCompletionHandler implements CheckoutCompletionHandler {

  private final MongoTemplate mongoTemplate;

  public CafeCheckoutCompletionHandler(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  public String getVerticalId() {
    return "cafe";
  }

  @Override
  public CheckoutCompletionResult onPurchaseCompleted(CheckoutCompletionContext context) {
    String tokenNo = allocateToken(context.getShopId());
    log.info("Allocated cafe token {} for purchase {}", tokenNo, context.getPurchaseId());
    return CheckoutCompletionResult.builder().tokenNo(tokenNo).build();
  }

  private String allocateToken(String shopId) {
    LocalDate businessDate = LocalDate.now();
    Query query =
        Query.query(
            Criteria.where("shopId")
                .is(shopId)
                .and("businessDate")
                .is(businessDate.toString()));
    Update update = new Update().inc("nextSequence", 1).setOnInsert("shopId", shopId).setOnInsert("businessDate", businessDate.toString());
    org.bson.Document counter =
        mongoTemplate.findAndModify(
            query,
            update,
            FindAndModifyOptions.options().returnNew(true).upsert(true),
            org.bson.Document.class,
            "cafe_token_counters");
    int seq = counter != null ? counter.getInteger("nextSequence", 1) : 1;
    return String.valueOf(seq);
  }
}
