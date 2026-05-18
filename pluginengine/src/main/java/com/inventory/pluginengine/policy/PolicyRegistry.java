package com.inventory.pluginengine.policy;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Slf4j
public class PolicyRegistry {

  private final Map<String, InventoryValidatorPolicy> inventoryValidators;

  public PolicyRegistry(Map<String, InventoryValidatorPolicy> inventoryValidatorBeans) {
    this.inventoryValidators = inventoryValidatorBeans;
  }

  @PostConstruct
  void logPolicies() {
    log.info("Policy registry ready with inventory validators: {}", inventoryValidators.keySet());
    if (!inventoryValidators.containsKey("pharmacyInventory")) {
      throw new IllegalStateException("Required policy bean missing: pharmacyInventory");
    }
  }

  public InventoryValidatorPolicy inventoryValidator(String strategyKey) {
    if (strategyKey == null || strategyKey.isBlank()) {
      strategyKey = "pharmacyInventory";
    }
    InventoryValidatorPolicy policy = inventoryValidators.get(strategyKey);
    if (policy == null) {
      throw new IllegalStateException("Unknown inventoryValidator policy: " + strategyKey);
    }
    return policy;
  }
}
