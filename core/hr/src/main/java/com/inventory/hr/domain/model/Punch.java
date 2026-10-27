package com.inventory.hr.domain.model;

import com.inventory.hr.domain.model.enums.PunchDirection;
import com.inventory.hr.domain.model.enums.PunchFlag;
import com.inventory.hr.domain.model.enums.PunchSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One recorded moment an employee was seen (design D1). Raw store; attendance days are derived
 * from these by {@code PunchDayDeriver}. Never deleted by the app. Unique {@code (shopId,
 * dedupeKey)}.
 *
 * <p>Privacy (R3.6): the employee's own coordinates are never stored here, only the distance from
 * the shop and the accuracy the phone reported.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "hr_punches")
public class Punch {

  @Id private String id;
  private String shopId;
  private String employeeId;
  /** Server time of the punch (never the phone's). */
  private Instant at;
  /** yyyy-MM-dd of {@code at} in Asia/Kolkata. */
  private String localDate;
  private PunchSource source;
  private PunchDirection direction;
  /** Whole metres from the shop; null when the location check was off or no location given. */
  private Integer distanceMetres;
  /** Accuracy the phone reported, whole metres; null when not given or not checked. */
  private Integer accuracyMetres;
  private List<PunchFlag> flags = new ArrayList<>();
  /** Self check-in: {@code S:<employeeId>:<epochSecond>}. */
  private String dedupeKey;
  private Instant createdAt;
}
