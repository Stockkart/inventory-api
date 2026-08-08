package com.inventory.plugins.grocery;

import com.inventory.pluginengine.ConfiguredVerticalPlugin;
import com.inventory.pluginengine.InventoryExtensionRepository;
import com.inventory.pluginengine.InventorySearchProvider;
import com.inventory.pluginengine.InventoryVerticalValidator;
import com.inventory.pluginengine.SchemaDrivenInventoryValidator;
import com.inventory.plugins.grocery.domain.model.GroceryVerticalProperties;
import com.inventory.plugins.grocery.domain.repository.GroceryInventoryExtensionRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class GroceryPlugin extends ConfiguredVerticalPlugin {

  private final InventoryVerticalValidator inventoryValidator;
  private final GroceryInventoryExtensionRepository extensionRepository;
  private final GroceryInventorySearchProvider searchProvider;

  public GroceryPlugin(
      GroceryVerticalProperties properties,
      GroceryInventoryExtensionRepository extensionRepository,
      GroceryInventorySearchProvider searchProvider) {
    super(properties.getId(), properties.getVersion());
    this.inventoryValidator = new SchemaDrivenInventoryValidator(properties.getId());
    this.extensionRepository = extensionRepository;
    this.searchProvider = searchProvider;
  }

  @Override
  public Optional<InventoryVerticalValidator> getInventoryValidator() {
    return Optional.of(inventoryValidator);
  }

  @Override
  public Optional<InventoryExtensionRepository> getInventoryExtensionRepository() {
    return Optional.of(extensionRepository);
  }

  @Override
  public Optional<InventorySearchProvider> getSearchProvider() {
    return Optional.of(searchProvider);
  }
}
