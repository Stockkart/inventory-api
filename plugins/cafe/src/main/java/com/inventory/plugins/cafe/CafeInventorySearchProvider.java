package com.inventory.plugins.cafe;

import com.inventory.pluginengine.defaultprovider.SchemaDrivenInventorySearchProvider;
import com.inventory.plugins.cafe.domain.CafeInventoryExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
public class CafeInventorySearchProvider extends SchemaDrivenInventorySearchProvider {

  public CafeInventorySearchProvider(MongoTemplate mongoTemplate) {
    super(mongoTemplate, CafeInventoryExtension.class);
  }

  @Override
  public String getVerticalId() {
    return "cafe";
  }
}
