package com.inventory.product.domain.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.util.StringUtils;

import com.inventory.product.domain.model.Purchase;

public class PurchaseCustomRepositoryImpl implements PurchaseCustomRepository {

  @Autowired
  private MongoTemplate mongoTemplate;

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
