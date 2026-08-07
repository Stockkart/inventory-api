package com.inventory.plugins.fmcg;

import com.inventory.pluginengine.ConfiguredVerticalPlugin;
import com.inventory.pluginengine.InventoryExtensionRepository;
import com.inventory.pluginengine.InventorySearchProvider;
import com.inventory.pluginengine.InventoryVerticalValidator;
import com.inventory.pluginengine.SchemaDrivenInventoryValidator;
import com.inventory.plugins.fmcg.domain.model.FmcgVerticalProperties;
import com.inventory.plugins.fmcg.domain.repository.FmcgInventoryExtensionRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class FmcgPlugin extends ConfiguredVerticalPlugin {

  private final InventoryVerticalValidator inventoryValidator;
  private final FmcgInventoryExtensionRepository extensionRepository;
  private final FmcgInventorySearchProvider searchProvider;

  public FmcgPlugin(
      FmcgVerticalProperties properties,
      FmcgInventoryExtensionRepository extensionRepository,
      FmcgInventorySearchProvider searchProvider) {
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
