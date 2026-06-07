package com.inventory.plugins.sports;

import com.inventory.pluginengine.ConfiguredVerticalPlugin;
import com.inventory.pluginengine.InventoryVerticalValidator;
import com.inventory.pluginengine.SchemaDrivenInventoryValidator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class SportsPlugin extends ConfiguredVerticalPlugin {

  private final InventoryVerticalValidator inventoryValidator;

  public SportsPlugin(SportsVerticalProperties properties) {
    super(properties.getId(), properties.getVersion());
    this.inventoryValidator =
        new SchemaDrivenInventoryValidator(properties.getId(), null, List.of());
  }

  @Override
  public Optional<InventoryVerticalValidator> getInventoryValidator() {
    return Optional.of(inventoryValidator);
  }
}
