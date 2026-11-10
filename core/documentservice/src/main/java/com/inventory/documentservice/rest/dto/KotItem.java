package com.inventory.documentservice.rest.dto;

import lombok.Data;

/** One line on a kitchen ticket. Deliberately carries no price, tax or total. */
@Data
public class KotItem {

  private String name;
  private Integer quantity;
  private String note;
}
