package com.inventory.plugins.medical;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "vertical.plugins.medical")
public class MedicalVerticalProperties {

  private String id;
  private String version;
}
