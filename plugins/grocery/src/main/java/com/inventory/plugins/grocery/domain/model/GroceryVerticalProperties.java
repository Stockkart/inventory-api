package com.inventory.plugins.grocery.domain.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "vertical.plugins.grocery")
public class GroceryVerticalProperties {

  private String id;
  private String version;
}
