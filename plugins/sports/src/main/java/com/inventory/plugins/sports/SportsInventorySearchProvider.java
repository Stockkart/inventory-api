package com.inventory.plugins.sports;

import com.inventory.pluginengine.InventorySearchProvider;
import com.inventory.pluginengine.InventorySearchQuery;
import com.inventory.pluginengine.InventorySearchResult;
import com.inventory.plugins.sports.domain.SportsInventoryExtension;
import java.util.ArrayList;
import java.util.List;
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
    if (query.getFilters() != null) {
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

    int limit = query.getLimit() > 0 ? Math.min(query.getLimit(), 200) : 50;
    Query mongoQuery = new Query(criteria).limit(limit);
    mongoQuery.with(Sort.by(Sort.Direction.ASC, "brand", "sport"));

    List<SportsInventoryExtension> docs =
        mongoTemplate.find(mongoQuery, SportsInventoryExtension.class);
    List<String> ids = new ArrayList<>();
    for (SportsInventoryExtension doc : docs) {
      if (StringUtils.hasText(doc.getInventoryId())) {
        ids.add(doc.getInventoryId());
      }
    }
    return InventorySearchResult.builder().inventoryIds(ids).totalMatched(ids.size()).build();
  }
}
