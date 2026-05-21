package com.inventory.product.rest.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BusinessProfileResponse {

  private String id;
  private String code;
  private String name;
  private Integer version;
  private Map<String, Boolean> modules;
  private Map<String, EntityProfileResponse> entities;
  private Map<String, String> strategies;
  private Map<String, Object> pricing;
  private ProfileUiResponse ui;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class EntityProfileResponse {
    private List<FieldDefinitionResponse> fields;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class FieldDefinitionResponse {
    private String key;
    private Boolean required;
    private Boolean visible;
    private String storage;
    private String label;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class ProfileUiResponse {
    private List<String> navHidden;
  }
}
