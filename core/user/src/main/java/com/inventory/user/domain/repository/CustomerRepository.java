package com.inventory.user.domain.repository;

import com.inventory.user.domain.model.Customer;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerRepository extends MongoRepository<Customer, String> {

  /**
   * Find a customer by phone.
   *
   * @param phone the phone number
   * @return an Optional containing the customer if found, empty otherwise
   */
  Optional<Customer> findByPhone(String phone);

  /**
   * Find a customer by email.
   *
   * @param email the email address
   * @return an Optional containing the customer if found, empty otherwise
   */
  Optional<Customer> findByEmail(String email);

  /**
   * Find all customers linked to a user account.
   *
   * @param userId the user account ID
   * @return list of customers linked to the user
   */
  List<Customer> findByUserId(String userId);

  /**
   * Search customers by query (name, phone, email, or address).
   *
   * @param query the search query
   * @return list of matching customers
   */
  @Query("{ $or: [ " +
      "{ 'name': { $regex: ?0, $options: 'i' } }, " +
      "{ 'phone': { $regex: ?0, $options: 'i' } }, " +
      "{ 'email': { $regex: ?0, $options: 'i' } }, " +
      "{ 'address': { $regex: ?0, $options: 'i' } } " +
      "] }")
  List<Customer> searchByQuery(String query);
}

