package com.inventory.plugins.medical;

import com.inventory.pluginengine.ExtensionFieldCoercion;
import com.inventory.pluginengine.InventoryExpiryBucketSummary;
import com.inventory.pluginengine.InventorySearchProvider;
import com.inventory.pluginengine.InventorySearchQuery;
import com.inventory.pluginengine.InventorySearchResult;
import com.inventory.plugins.medical.domain.MedicalInventoryExtension;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class MedicalInventorySearchProvider implements InventorySearchProvider {

  private final MongoTemplate mongoTemplate;

  public MedicalInventorySearchProvider(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  public String getVerticalId() {
    return "medical";
  }

  @Override
  public InventorySearchResult search(String shopId, InventorySearchQuery query) {
    Criteria criteria = Criteria.where("shopId").is(shopId);
    if (query.getFilters() != null) {
      query
          .getFilters()
          .forEach(
              (key, raw) -> {
                if (!StringUtils.hasText(raw)) {
                  return;
                }
                switch (key) {
                  case "batchNo" -> criteria.and("batchNo").is(raw.trim());
                  case "expiryBefore" ->
                      criteria.and("expiryDate").lte(ExtensionFieldCoercion.asInstant(raw.trim()));
                  case "expiryAfter" ->
                      criteria.and("expiryDate").gte(ExtensionFieldCoercion.asInstant(raw.trim()));
                  case "nearExpiryDays" -> {
                    int days = Integer.parseInt(raw.trim());
                    Instant now = Instant.now();
                    criteria
                        .and("expiryDate")
                        .gte(now)
                        .lte(now.plusSeconds((long) days * 86_400));
                  }
                  default -> {}
                }
              });
    }

    int limit = query.getLimit() > 0 ? Math.min(query.getLimit(), 200) : 50;
    Query mongoQuery = new Query(criteria).limit(limit);
    if (StringUtils.hasText(query.getSort()) && query.getSort().startsWith("expiryDate")) {
      boolean asc = !query.getSort().endsWith(":desc");
      mongoQuery.with(Sort.by(asc ? Sort.Direction.ASC : Sort.Direction.DESC, "expiryDate"));
    } else {
      mongoQuery.with(Sort.by(Sort.Direction.ASC, "expiryDate"));
    }

    List<MedicalInventoryExtension> docs =
        mongoTemplate.find(mongoQuery, MedicalInventoryExtension.class);
    List<String> ids = new ArrayList<>();
    for (MedicalInventoryExtension doc : docs) {
      if (StringUtils.hasText(doc.getInventoryId())) {
        ids.add(doc.getInventoryId());
      }
    }
    return InventorySearchResult.builder().inventoryIds(ids).totalMatched(ids.size()).build();
  }

  @Override
  public InventoryExpiryBucketSummary aggregateExpiryBuckets(String shopId, int expiringSoonDays) {
    Instant now = Instant.now();
    int windowDays = expiringSoonDays > 0 ? expiringSoonDays : 30;
    Instant soonEnd = now.plusSeconds((long) windowDays * 86_400);
    Instant weekEnd = now.plusSeconds(7L * 86_400);

    int expired =
        (int)
            mongoTemplate.count(
                new Query(
                    Criteria.where("shopId")
                        .is(shopId)
                        .and("expiryDate")
                        .exists(true)
                        .lt(now)),
                MedicalInventoryExtension.class);

    int expiringWithin7Days =
        (int)
            mongoTemplate.count(
                new Query(
                    Criteria.where("shopId")
                        .is(shopId)
                        .and("expiryDate")
                        .gte(now)
                        .lte(weekEnd)),
                MedicalInventoryExtension.class);

    int expiringWithinSoonDays =
        (int)
            mongoTemplate.count(
                new Query(
                    Criteria.where("shopId")
                        .is(shopId)
                        .and("expiryDate")
                        .gt(weekEnd)
                        .lte(soonEnd)),
                MedicalInventoryExtension.class);

    int totalWithExpiry =
        (int)
            mongoTemplate.count(
                new Query(
                    Criteria.where("shopId").is(shopId).and("expiryDate").exists(true)),
                MedicalInventoryExtension.class);

    return InventoryExpiryBucketSummary.builder()
        .expired(expired)
        .expiringWithin7Days(expiringWithin7Days)
        .expiringWithinSoonDays(expiringWithinSoonDays)
        .totalWithExpiry(totalWithExpiry)
        .expiringSoonDays(windowDays)
        .build();
  }

  @Override
  public InventorySearchResult searchFefo(String shopId, String batchNo, int limit) {
    InventorySearchQuery.InventorySearchQueryBuilder builder =
        InventorySearchQuery.builder().sort("expiryDate:asc").limit(limit > 0 ? limit : 50);
    if (StringUtils.hasText(batchNo)) {
      builder.filters(java.util.Map.of("batchNo", batchNo.trim()));
    }
    return search(shopId, builder.build());
  }
}
