package com.inventory.documentservice.rest.dto;

import com.inventory.documentservice.domain.KotStamp;
import java.util.List;
import lombok.Data;

/**
 * Everything a kitchen ticket prints.
 *
 * <p>There is no shop, customer, price or tax field here, and that is the point: a KOT is a
 * production document, not a financial one.
 */
@Data
public class GenerateKotRequest {

  private Integer kotNo;
  private Integer orderNo;
  private String orderType;
  private String tableLabel;
  private String tokenNo;
  private String department;
  private Integer roundNo;
  private String printedAt;
  private String stewardName;
  private List<KotItem> items;
  private KotStamp stamp;
  private String voidReason;
}
