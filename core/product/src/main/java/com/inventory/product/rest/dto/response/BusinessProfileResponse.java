package com.inventory.product.rest.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BusinessProfileResponse {

  private String id;
  private String code;
  private String name;
  private int version;
  private Map<String, Boolean> modules;
  private Map<String, Object> entities;
  private Map<String, Object> pricing;
  private Map<String, String> strategies;
  private Map<String, Object> compliance;
  private Map<String, Object> ui;
}
