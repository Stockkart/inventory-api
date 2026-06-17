package com.inventory.plugins.sports;

import com.inventory.plugins.search.support.SchemaDrivenInventorySearchProvider;
import com.inventory.plugins.sports.domain.SportsInventoryExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
public class SportsInventorySearchProvider extends SchemaDrivenInventorySearchProvider {

  public SportsInventorySearchProvider(MongoTemplate mongoTemplate) {
    super(mongoTemplate, SportsInventoryExtension.class);
  }

  @Override
  public String getVerticalId() {
    return "sports";
  }
}
