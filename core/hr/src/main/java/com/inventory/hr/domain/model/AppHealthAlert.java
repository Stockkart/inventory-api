package com.inventory.hr.domain.model;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A shop's open or cleared signature-failure alert (R10.4–R10.6). Raised when more than 1% of at
 * least 20 app punches in a rolling 60-minute window failed verification; cleared once a full
 * window stays at or below 1%. At most one open row ({@code clearedAt == null}) per shop.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "hr_app_alerts")
public class AppHealthAlert {

  @Id private String id;
  private String shopId;
  /** failures / attempts over the window when raised, e.g. 0.05 = 5%. */
  private Double rate;
  /** Punches with result "signature failed" in the window when raised. */
  private Integer failed;
  /** App punch attempts in the window when raised. */
  private Integer attempts;
  private Instant raisedAt;
  /** null = still open. */
  private Instant clearedAt;
}
