package com.inventory.product.rest.dto.request;

import java.util.List;
import lombok.Data;

@Data
public class PunchKotRequest {

  private List<Line> lines;

  @Data
  public static class Line {
    private String sellableRef;
    private Integer quantity;

    /** Free-text preparation note, e.g. "no onion". */
    private String note;
  }
}
