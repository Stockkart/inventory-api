package com.inventory.user.domain.repository;

import com.inventory.user.domain.model.Vendor;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VendorRepository extends MongoRepository<Vendor, String> {

  /**
   * Find a vendor by contact email.
   *
   * @param contactEmail the contact email
   * @return an Optional containing the vendor if found, empty otherwise
   */
  Optional<Vendor> findByContactEmail(String contactEmail);

  /**
   * Find a vendor by contact phone.
   *
   * @param contactPhone the contact phone
   * @return an Optional containing the vendor if found, empty otherwise
   */
  Optional<Vendor> findByContactPhone(String contactPhone);

  /**
   * Search vendors by query (name, company name, or contact email).
   *
   * @param query the search query
   * @return list of matching vendors
   */
  @Query("{ $or: [ " +
      "{ 'name': { $regex: ?0, $options: 'i' } }, " +
      "{ 'companyName': { $regex: ?0, $options: 'i' } }, " +
      "{ 'contactEmail': { $regex: ?0, $options: 'i' } } " +
      "] }")
  List<Vendor> searchByQuery(String query);
}

