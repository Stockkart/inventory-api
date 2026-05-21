package com.inventory.pluginengine.profile;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProfileUi {

  private List<String> navHidden;

  public List<String> getNavHidden() {
    return navHidden != null ? navHidden : Collections.emptyList();
  }

  public boolean isPathHidden(String path) {
    return path != null && getNavHidden().contains(path);
  }
}
