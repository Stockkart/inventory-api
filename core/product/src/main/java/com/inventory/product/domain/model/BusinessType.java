package com.inventory.product.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "business_types")
public class BusinessType {

  @Id
  private String id;
  private String code;
  private String name;
  private boolean enabled;
  private Map<String, Object> registeredAttributes;
  private Map<String, Object> registeredTaxRules;
  private Instant registeredAt;
}

