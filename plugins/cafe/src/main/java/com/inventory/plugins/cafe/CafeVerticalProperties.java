package com.inventory.plugins.cafe;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "vertical.plugins.cafe")
public class CafeVerticalProperties {

  private String id;
  private String version;
}
