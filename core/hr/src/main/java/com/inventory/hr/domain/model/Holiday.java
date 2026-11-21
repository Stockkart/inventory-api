package com.inventory.hr.domain.model;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** A paid shop holiday (R3); unique {@code (shopId, date)}. No field is ever null. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "hr_holidays")
public class Holiday {

  @Id private String id;
  private String shopId;
  /** yyyy-MM-dd. */
  private String date;
  private String name;
  /** Year of {@code date}, for the per-year list. */
  private Integer year;
  private Instant createdAt;
  private Instant updatedAt;
  private String updatedByUserId;
}
