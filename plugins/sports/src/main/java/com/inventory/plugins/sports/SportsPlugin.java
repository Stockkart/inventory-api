package com.inventory.plugins.sports;

import com.inventory.pluginengine.InventoryVerticalValidator;
import com.inventory.pluginengine.SchemaDrivenInventoryValidator;
import com.inventory.pluginengine.VerticalConstants;
import com.inventory.pluginengine.VerticalPlugin;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class SportsPlugin implements VerticalPlugin {

  private final InventoryVerticalValidator inventoryValidator =
      new SchemaDrivenInventoryValidator("sports", null, List.of());

  @Override
  public String getVerticalId() {
    return VerticalConstants.SPORTS;
  }

  @Override
  public Optional<InventoryVerticalValidator> getInventoryValidator() {
    return Optional.of(inventoryValidator);
  }
}
