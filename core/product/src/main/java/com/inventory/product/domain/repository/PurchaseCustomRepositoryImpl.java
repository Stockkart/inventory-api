package com.inventory.product.domain.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.util.StringUtils;

import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.enums.PurchaseStatus;

public class PurchaseCustomRepositoryImpl implements PurchaseCustomRepository {

  @Autowired
  private MongoTemplate mongoTemplate;

  @Override
  public List<Purchase> findRecentForCustomerMatching(
      String shopId,
      String customerId,
      String excludePurchaseId,
      Collection<String> sellableRefs,
      Collection<String> menuItemIds,
      Collection<String> productNames,
      int limit) {

    List<Criteria> itemMatchers = new ArrayList<>();
    if (sellableRefs != null && !sellableRefs.isEmpty()) {
      itemMatchers.add(Criteria.where("items.sellableRef").in(sellableRefs));
    }
    if (menuItemIds != null && !menuItemIds.isEmpty()) {
      itemMatchers.add(Criteria.where("items.menuItemId").in(menuItemIds));
    }
    // The product name is what reaches a sale from an earlier batch, and the
    // only thing that reaches one whose lot has since been consumed.
    if (productNames != null && !productNames.isEmpty()) {
      itemMatchers.add(Criteria.where("items.name").in(productNames));
    }

    Criteria base = Criteria.where("shopId").is(shopId)
        .and("customerId").is(customerId)
        .and("status").is(PurchaseStatus.COMPLETED);
    if (StringUtils.hasText(excludePurchaseId)) {
      base = base.and("_id").ne(excludePurchaseId.trim());
    }

    Criteria filter = base;
    if (!itemMatchers.isEmpty()) {
      filter = new Criteria().andOperator(
          base,
          new Criteria().orOperator(itemMatchers.toArray(new Criteria[0])));
    }

    Query query = new Query(filter)
        .with(Sort.by(Sort.Direction.DESC, "soldAt"))
        .limit(limit);
    return mongoTemplate.find(query, Purchase.class);
  }

  @Override
  public Page<Purchase> search(
      String shopId,
      String invoiceNo,
      Instant soldFrom,
      Instant soldTo,
      Collection<String> customerIds,
      Pageable pageable) {

    Criteria criteria = Criteria.where("shopId").is(shopId);
    if (StringUtils.hasText(invoiceNo)) {
      // Quoted, so a number containing a regex character is searched for
      // literally rather than being read as a pattern.
      criteria = criteria.and("invoiceNo")
          .regex(Pattern.quote(invoiceNo.trim()), "i");
    }
    if (soldFrom != null || soldTo != null) {
      Criteria soldAt = Criteria.where("soldAt");
      if (soldFrom != null) {
        soldAt = soldAt.gte(soldFrom);
      }
      if (soldTo != null) {
        soldAt = soldAt.lt(soldTo);
      }
      criteria = new Criteria().andOperator(criteria, soldAt);
    }
    if (customerIds != null && !customerIds.isEmpty()) {
      criteria = new Criteria().andOperator(
          criteria, Criteria.where("customerId").in(customerIds));
    }

    Query query = new Query(criteria);
    long total = mongoTemplate.count(query, Purchase.class);
    List<Purchase> page = mongoTemplate.find(query.with(pageable), Purchase.class);
    return new PageImpl<>(page, pageable, total);
  }
}
