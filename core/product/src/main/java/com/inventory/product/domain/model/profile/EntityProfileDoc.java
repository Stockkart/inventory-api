package com.inventory.product.domain.model.profile;

import lombok.Data;

import java.util.List;

@Data
public class EntityProfileDoc {

  private List<FieldDefinitionDoc> fields;
}
