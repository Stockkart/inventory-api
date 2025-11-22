package com.inventory.plugins.medical;

import com.inventory.pluginengine.ProductPlugin;
import lombok.NoArgsConstructor;

@NoArgsConstructor
public class MedicalPlugin implements ProductPlugin {

  public String getPluginId() {
    return "MEDICAL-PLUGIN";
  }
}
