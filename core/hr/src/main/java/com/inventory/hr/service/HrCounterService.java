package com.inventory.hr.service;

import com.inventory.hr.domain.model.HrCounter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Per-shop sequences in {@code hr_counters}. One atomic {@code findAndModify} with {@code $inc}
 * and upsert, so two concurrent callers never get the same number (no transaction needed).
 */
@Slf4j
@Service
public class HrCounterService {

  static final String EMPLOYEE_CODE = "EMPLOYEE_CODE";

  @Autowired private MongoTemplate mongoTemplate;

  /** Next value of {@code shopId:name}, starting at 1. */
  public long next(String shopId, String name) {
    Query query = Query.query(Criteria.where("_id").is(shopId + ":" + name));
    Update update = new Update().inc("seq", 1L).setOnInsert("shopId", shopId);
    HrCounter counter = mongoTemplate.findAndModify(query, update,
        FindAndModifyOptions.options().upsert(true).returnNew(true), HrCounter.class);
    return counter != null && counter.getSeq() != null ? counter.getSeq() : 1L;
  }

  /** {@code EMP-001}, {@code EMP-002}, … ({@code EMP-1000} after 999). */
  public String nextEmployeeCode(String shopId) {
    return formatEmployeeCode(next(shopId, EMPLOYEE_CODE));
  }

  static String formatEmployeeCode(long seq) {
    return String.format("EMP-%03d", seq);
  }
}
