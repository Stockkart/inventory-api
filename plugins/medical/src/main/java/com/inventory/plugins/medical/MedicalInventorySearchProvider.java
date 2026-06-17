package com.inventory.plugins.medical;

import com.inventory.plugins.search.support.SchemaDrivenInventorySearchProvider;
import com.inventory.pluginengine.ExtensionFieldCoercion;
import com.inventory.pluginengine.InventoryExpiryBucketSummary;
import com.inventory.pluginengine.InventorySearchQuery;
import com.inventory.pluginengine.InventorySearchResult;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.plugins.medical.domain.MedicalInventoryExtension;
import com.inventory.plugins.medical.search.MedicalSearchSchema;
import java.time.Instant;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class MedicalInventorySearchProvider extends SchemaDrivenInventorySearchProvider {

  private final MongoTemplate mongoTemplate;

  public MedicalInventorySearchProvider(MongoTemplate mongoTemplate) {
    super(mongoTemplate, MedicalInventoryExtension.class);
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  public String getVerticalId() {
    return "medical";
  }

  @Override
  protected void applyVerticalFilters(
      Criteria criteria, InventorySearchQuery query, VerticalSchema schema) {
    if (query.getFilters() == null) {
      return;
    }
    query
        .getFilters()
        .forEach(
            (key, raw) -> {
              if (!StringUtils.hasText(raw)) {
                return;
              }
              switch (key) {
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

    return InventoryExpiryBucketSummary.builder()
        .expired(expired)
        .expiringWithin7Days(expiringWithin7Days)
        .expiringWithinSoonDays(expiringWithinSoonDays)
        .expiringSoonDays(windowDays)
        .build();
  }

  @Override
  public InventorySearchResult searchFefo(String shopId, String batchNo, int limit) {
    InventorySearchQuery.InventorySearchQueryBuilder builder =
        InventorySearchQuery.builder()
            .sort("expiryDate:asc")
            .limit(limit > 0 ? limit : 50)
            .schema(MedicalSearchSchema.fallback());
    if (StringUtils.hasText(batchNo)) {
      builder.filters(java.util.Map.of("batchNo", batchNo.trim()));
    }
    return search(shopId, builder.build());
  }
}
