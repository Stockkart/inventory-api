package com.inventory.user.domain.repository;

import com.inventory.user.domain.model.Invitation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InvitationRepository extends MongoRepository<Invitation, String> {

  /**
   * Find all invitations for a specific shop.
   *
   * @param shopId the shop ID
   * @return list of invitations for the shop
   */
  List<Invitation> findByShopId(String shopId);

  /**
   * Find all invitations sent to a specific user.
   *
   * @param inviteeUserId the user ID of the invitee
   * @return list of invitations for the user
   */
  List<Invitation> findByInviteeUserId(String inviteeUserId);

  /**
   * Find all invitations sent to a specific email.
   *
   * @param inviteeEmail the email of the invitee
   * @return list of invitations for the email
   */
  List<Invitation> findByInviteeEmail(String inviteeEmail);

  /**
   * Find all invitations sent by a specific user.
   *
   * @param inviterUserId the user ID of the inviter
   * @return list of invitations sent by the user
   */
  List<Invitation> findByInviterUserIdAndShopId(String inviterUserId, String shopId);

  /**
   * Find pending invitation for a user and shop.
   *
   * @param inviteeUserId the user ID of the invitee
   * @param shopId the shop ID
   * @return optional invitation if found
   */
  Optional<Invitation> findByInviteeUserIdAndShopIdAndStatus(String inviteeUserId, String shopId, String status);

  /**
   * Find pending invitation by email and shop.
   *
   * @param inviteeEmail the email of the invitee
   * @param shopId the shop ID
   * @return optional invitation if found
   */
  Optional<Invitation> findByInviteeEmailAndShopIdAndStatus(String inviteeEmail, String shopId, String status);

  /**
   * Find all accepted invitations for a shop.
   *
   * @param shopId the shop ID
   * @return list of accepted invitations
   */
  @Query("{ 'shopId': ?0, 'status': 'ACCEPTED' }")
  List<Invitation> findAcceptedInvitationsByShopId(String shopId);

  /**
   * Find all accepted invitations for a user.
   *
   * @param inviteeUserId the user ID
   * @return list of accepted invitations
   */
  @Query("{ 'inviteeUserId': ?0, 'status': 'ACCEPTED' }")
  List<Invitation> findAcceptedInvitationsByUserId(String inviteeUserId);
}

