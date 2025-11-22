package com.inventory.pluginengine;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Component
public class PluginManager {

  private final List<ProductPlugin> productPlugins;

  public PluginManager(Map<String, ProductPlugin> productPlugins) {
    ServiceLoader<ProductPlugin> loader = ServiceLoader.load(ProductPlugin.class);
    this.productPlugins = StreamSupport.stream(loader.spliterator(), false)
            .collect(Collectors.toList());
  }

  public String getCurrentPlugin() {
    return productPlugins.getFirst().getPluginId();
  }
}
