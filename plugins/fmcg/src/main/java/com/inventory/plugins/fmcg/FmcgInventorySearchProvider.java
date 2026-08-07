package com.inventory.plugins.fmcg;

import com.inventory.pluginengine.ExtensionFieldCoercion;
import com.inventory.pluginengine.InventoryExpiryBucketSummary;
import com.inventory.pluginengine.InventorySearchQuery;
import com.inventory.pluginengine.InventorySearchResult;
import com.inventory.pluginengine.defaultprovider.SchemaDrivenInventorySearchProvider;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.plugins.fmcg.domain.model.FmcgInventoryExtension;
import com.inventory.plugins.fmcg.search.FmcgSearchSchema;
import java.time.Instant;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class FmcgInventorySearchProvider extends SchemaDrivenInventorySearchProvider {

  private final MongoTemplate mongoTemplate;

  public FmcgInventorySearchProvider(MongoTemplate mongoTemplate) {
    super(mongoTemplate, FmcgInventoryExtension.class);
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  public String getVerticalId() {
    return "fmcg";
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
                    Criteria.where("shopId").is(shopId).and("expiryDate").exists(true).lt(now)),
                FmcgInventoryExtension.class);

    int expiringWithin7Days =
        (int)
            mongoTemplate.count(
                new Query(
                    Criteria.where("shopId").is(shopId).and("expiryDate").gte(now).lte(weekEnd)),
                FmcgInventoryExtension.class);

    int expiringWithinSoonDays =
        (int)
            mongoTemplate.count(
                new Query(
                    Criteria.where("shopId")
                        .is(shopId)
                        .and("expiryDate")
                        .gt(weekEnd)
                        .lte(soonEnd)),
                FmcgInventoryExtension.class);

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
            .schema(FmcgSearchSchema.fallback());
    if (StringUtils.hasText(batchNo)) {
      builder.filters(java.util.Map.of("batchNo", batchNo.trim()));
    }
    return search(shopId, builder.build());
  }
}
