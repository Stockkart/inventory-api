package com.inventory.plugins.supermarket.domain.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "vertical.plugins.supermarket")
public class SupermarketVerticalProperties {

  private String id;
  private String version;
}
