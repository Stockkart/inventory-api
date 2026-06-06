package com.inventory.pluginengine;

import com.inventory.common.exception.ValidationException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class PluginRegistry {

  private final Map<String, VerticalPlugin> pluginsByVerticalId;

  public PluginRegistry(List<VerticalPlugin> plugins) {
    this.pluginsByVerticalId =
        plugins.stream()
            .collect(
                Collectors.toMap(
                    p -> normalizeVerticalId(p.getVerticalId()),
                    Function.identity(),
                    (a, b) -> {
                      throw new IllegalStateException(
                          "Duplicate vertical plugin registration: " + a.getVerticalId());
                    }));
  }

  public Optional<VerticalPlugin> find(String verticalId) {
    if (verticalId == null || verticalId.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(pluginsByVerticalId.get(normalizeVerticalId(verticalId)));
  }

  public VerticalPlugin require(String verticalId) {
    return find(verticalId)
        .orElseThrow(
            () -> new ValidationException("No plugin registered for vertical: " + verticalId));
  }

  public Collection<VerticalPlugin> all() {
    return pluginsByVerticalId.values();
  }

  private static String normalizeVerticalId(String verticalId) {
    return verticalId.trim().toLowerCase();
  }
}
