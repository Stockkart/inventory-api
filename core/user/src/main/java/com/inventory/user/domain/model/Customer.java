package com.inventory.user.domain.model;

import com.inventory.common.gst.PostalAddress;
import com.inventory.user.domain.model.enums.CustomerPartyType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "customers")
public class Customer {

  @Id
  private String id;
  private String name;
  /** Sparse unique identity keys — application enforces match order; indexes aid lookups. */
  @Indexed(sparse = true)
  private String phone;
  /** Free-text address as typed; kept for bills and for customers saved before fielded addresses. */
  private String address;
  /** Fielded address; the state code here is what decides CGST+SGST vs IGST on a sale. */
  private PostalAddress postalAddress;
  @Indexed(sparse = true)
  private String email;
  @Indexed(sparse = true)
  private String gstin;
  /** When the GSTIN was last confirmed against the GST network; null if never. */
  private Instant gstinVerifiedAt;
  /** Status the GST network reported (Active, Cancelled, ...); null if unverified. */
  private String gstinStatus;
  @Indexed(sparse = true)
  private String dlNo;
  @Indexed(sparse = true)
  private String pan;
  /** Optional link to UserAccount when customer is a registered user. */
  private String userId;
  /** Buyer classification; default CONSUMER when null (legacy rows). */
  private CustomerPartyType partyType;
  /**
   * Shop-scoped walk-in / name-address-only placeholder. One per shop via shop_customers.
   * Never created via normal POST /customers.
   */
  private Boolean isGeneral;
  private Instant createdAt;
  private Instant updatedAt;

  public boolean isGeneralCustomer() {
    return Boolean.TRUE.equals(isGeneral);
  }

  public CustomerPartyType resolvedPartyType() {
    return partyType != null ? partyType : CustomerPartyType.CONSUMER;
  }
}
