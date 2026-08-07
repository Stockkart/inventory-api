package com.inventory.plugins.fmcg.domain.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "vertical.plugins.fmcg")
public class FmcgVerticalProperties {

  private String id;
  private String version;
}
