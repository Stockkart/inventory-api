package com.inventory.pluginengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.inventory.common.exception.ValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;

class PluginRegistryTest {

  @Test
  void findsRegisteredPluginByVerticalId() {
    PluginRegistry registry = new PluginRegistry(List.of(new StubPlugin("medical")));

    VerticalPlugin plugin = registry.require("medical");
    assertEquals("medical", plugin.getVerticalId());
  }

  @Test
  void normalizesVerticalIdCase() {
    PluginRegistry registry = new PluginRegistry(List.of(new StubPlugin("medical")));

    assertEquals("medical", registry.require("MEDICAL").getVerticalId());
  }

  @Test
  void throwsWhenVerticalMissing() {
    PluginRegistry registry = new PluginRegistry(List.of(new StubPlugin("medical")));

    assertThrows(ValidationException.class, () -> registry.require("apparel"));
  }

  private static final class StubPlugin implements VerticalPlugin {
    private final String verticalId;

    private StubPlugin(String verticalId) {
      this.verticalId = verticalId;
    }

    @Override
    public String getVerticalId() {
      return verticalId;
    }

    @Override
    public String getPluginVersion() {
      return "1.0.0";
    }
  }
}
