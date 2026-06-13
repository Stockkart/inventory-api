package com.inventory.plugins.medical;

import com.inventory.pluginengine.ConfiguredVerticalPlugin;
import com.inventory.pluginengine.InventoryExtensionRepository;
import com.inventory.pluginengine.InventoryVerticalValidator;
import com.inventory.pluginengine.SchemaDrivenInventoryValidator;
import com.inventory.plugins.medical.repository.MedicalInventoryExtensionRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class MedicalPlugin extends ConfiguredVerticalPlugin {

  private final InventoryVerticalValidator inventoryValidator;
  private final MedicalInventoryExtensionRepository extensionRepository;

  public MedicalPlugin(
      MedicalVerticalProperties properties,
      MedicalInventoryExtensionRepository extensionRepository) {
    super(properties.getId(), properties.getVersion());
    this.inventoryValidator = new SchemaDrivenInventoryValidator(properties.getId());
    this.extensionRepository = extensionRepository;
  }

  @Override
  public Optional<InventoryVerticalValidator> getInventoryValidator() {
    return Optional.of(inventoryValidator);
  }

  @Override
  public Optional<InventoryExtensionRepository> getInventoryExtensionRepository() {
    return Optional.of(extensionRepository);
  }
}
