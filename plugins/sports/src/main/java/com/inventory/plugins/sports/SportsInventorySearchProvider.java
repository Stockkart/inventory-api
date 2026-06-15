package com.inventory.plugins.sports;

import com.inventory.pluginengine.InventorySearchProvider;
import com.inventory.pluginengine.InventorySearchQuery;
import com.inventory.pluginengine.InventorySearchResult;
import com.inventory.plugins.sports.domain.SportsInventoryExtension;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class SportsInventorySearchProvider implements InventorySearchProvider {

  private final MongoTemplate mongoTemplate;

  public SportsInventorySearchProvider(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  public String getVerticalId() {
    return "sports";
  }

  @Override
  public InventorySearchResult search(String shopId, InventorySearchQuery query) {
    Criteria criteria = Criteria.where("shopId").is(shopId);
    applyFilters(criteria, query);
    applyRestrictIds(criteria, query.getRestrictInventoryIds());

    int limit = query.getLimit() > 0 ? Math.min(query.getLimit(), 200) : 50;
    Query mongoQuery = new Query(criteria);
    mongoQuery.with(Sort.by(Sort.Direction.ASC, "brand", "sport", "inventoryId"));
    if (StringUtils.hasText(query.getCursor())) {
      // Sports uses brand/sport sort; cursor not supported — use skip only
      mongoQuery.skip(Math.max(query.getSkip(), 0));
    } else if (query.getSkip() > 0) {
      mongoQuery.skip(query.getSkip());
    }
    mongoQuery.limit(limit + 1);

    List<SportsInventoryExtension> docs =
        mongoTemplate.find(mongoQuery, SportsInventoryExtension.class);
    List<String> ids = new ArrayList<>();
    for (SportsInventoryExtension doc : docs) {
      if (ids.size() >= limit) {
        break;
      }
      if (StringUtils.hasText(doc.getInventoryId())) {
        ids.add(doc.getInventoryId());
      }
    }
    String nextCursor = docs.size() > limit && !ids.isEmpty() ? "skip:" + (query.getSkip() + limit) : null;
    return InventorySearchResult.builder()
        .inventoryIds(ids)
        .nextCursor(nextCursor)
        .totalMatched(ids.size())
        .build();
  }

  private static void applyRestrictIds(Criteria criteria, Set<String> restrictInventoryIds) {
    if (restrictInventoryIds == null || restrictInventoryIds.isEmpty()) {
      return;
    }
    criteria.and("inventoryId").in(restrictInventoryIds);
  }

  private static void applyFilters(Criteria criteria, InventorySearchQuery query) {
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
                case "sport" -> criteria.and("sport").is(raw.trim());
                case "brand" -> criteria.and("brand").is(raw.trim());
                case "model" -> criteria.and("model").is(raw.trim());
                default -> {}
              }
            });
  }
}
