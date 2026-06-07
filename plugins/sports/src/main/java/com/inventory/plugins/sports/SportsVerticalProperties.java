package com.inventory.plugins.sports;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "vertical.plugins.sports")
public class SportsVerticalProperties {

  private String id;
  private String version;
}
