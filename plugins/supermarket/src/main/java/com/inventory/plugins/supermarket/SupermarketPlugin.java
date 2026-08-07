package com.inventory.plugins.supermarket;

import com.inventory.pluginengine.ConfiguredVerticalPlugin;
import com.inventory.pluginengine.InventoryExtensionRepository;
import com.inventory.pluginengine.InventorySearchProvider;
import com.inventory.pluginengine.InventoryVerticalValidator;
import com.inventory.pluginengine.SchemaDrivenInventoryValidator;
import com.inventory.plugins.supermarket.domain.model.SupermarketVerticalProperties;
import com.inventory.plugins.supermarket.domain.repository.SupermarketInventoryExtensionRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class SupermarketPlugin extends ConfiguredVerticalPlugin {

  private final InventoryVerticalValidator inventoryValidator;
  private final SupermarketInventoryExtensionRepository extensionRepository;
  private final SupermarketInventorySearchProvider searchProvider;

  public SupermarketPlugin(
      SupermarketVerticalProperties properties,
      SupermarketInventoryExtensionRepository extensionRepository,
      SupermarketInventorySearchProvider searchProvider) {
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
