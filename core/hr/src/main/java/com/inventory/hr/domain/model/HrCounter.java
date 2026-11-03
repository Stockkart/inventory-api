package com.inventory.hr.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A per-shop sequence, {@code _id = shopId:<NAME>} (e.g. {@code shopId:EMPLOYEE_CODE}). Advanced
 * only by {@code findAndModify} with {@code $inc} and upsert in {@code HrCounterService}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "hr_counters")
public class HrCounter {

  @Id private String id;
  private String shopId;
  private Long seq;
}
