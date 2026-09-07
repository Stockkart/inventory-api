package com.inventory.user.domain.model;

import com.inventory.common.tax.PurchaseTaxTreatment;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "vendors")
public class Vendor {

  @Id
  private String id;
  private String name;
  private String contactEmail;
  private String contactPhone;
  private String address;
  private String companyName;
  private String businessType;
  private String gstinUin; // GSTIN or UIN (Unique Identification Number)
  private String dlNo; // Drug licence number, as printed on a pharmacy bill
  /** Optional link to UserAccount when vendor is a registered user. */
  private String userId;
  /**
   * How this supplier's bills state their line amounts: INCLUSIVE of GST or EXCLUSIVE of it.
   *
   * <p>A property of the supplier because it is a property of their billing software, not of any
   * one bill. It prefills the entry form so an operator is not asked the same question on every
   * invoice from the same vendor, and any individual bill can still say otherwise.
   *
   * <p>Null until someone says, and read as EXCLUSIVE -- the convention every path assumed before
   * the distinction was recorded.
   */
  private PurchaseTaxTreatment defaultTaxTreatment;
  private Instant createdAt;
  private Instant updatedAt;
}

