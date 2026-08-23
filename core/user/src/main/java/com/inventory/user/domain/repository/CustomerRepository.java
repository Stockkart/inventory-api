package com.inventory.user.domain.repository;

import com.inventory.user.domain.model.Customer;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerRepository extends MongoRepository<Customer, String> {

  Optional<Customer> findByPhone(String phone);

  Optional<Customer> findByEmail(String email);

  Optional<Customer> findByGstin(String gstin);

  Optional<Customer> findByPan(String pan);

  Optional<Customer> findByDlNo(String dlNo);

  List<Customer> findByUserId(String userId);

  List<Customer> findByNameIgnoreCase(String name);

  /**
   * Keyword search across identity and contact fields (excludes filtering general — done in service).
   */
  @Query("{ $or: [ " +
      "{ 'name': { $regex: ?0, $options: 'i' } }, " +
      "{ 'phone': { $regex: ?0, $options: 'i' } }, " +
      "{ 'email': { $regex: ?0, $options: 'i' } }, " +
      "{ 'address': { $regex: ?0, $options: 'i' } }, " +
      "{ 'gstin': { $regex: ?0, $options: 'i' } }, " +
      "{ 'pan': { $regex: ?0, $options: 'i' } }, " +
      "{ 'dlNo': { $regex: ?0, $options: 'i' } } " +
      "] }")
  List<Customer> searchByQuery(String query);
}
