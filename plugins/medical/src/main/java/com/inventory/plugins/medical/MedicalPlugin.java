package com.inventory.plugins.medical;

import com.inventory.pluginengine.ConfiguredVerticalPlugin;
import com.inventory.pluginengine.InventoryVerticalValidator;
import com.inventory.pluginengine.SchemaDrivenInventoryValidator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class MedicalPlugin extends ConfiguredVerticalPlugin {

  private final InventoryVerticalValidator inventoryValidator;

  public MedicalPlugin(MedicalVerticalProperties properties) {
    super(properties.getId(), properties.getVersion());
    this.inventoryValidator =
        new SchemaDrivenInventoryValidator(
            properties.getId(),
            Map.of("manufacturer", "companyName"),
            List.of(SchemaDrivenInventoryValidator.expiryNotInPastOnCreate()));
  }

  @Override
  public Optional<InventoryVerticalValidator> getInventoryValidator() {
    return Optional.of(inventoryValidator);
  }
}
