package com.inventory.user.api.port;

/**
 * What the user module needs from HR to decide who may see a worker account's photo (worker-app
 * R11.5): an approver reviewing that account's join request may. HR owns join requests and sits
 * above user, so HR implements this port in its {@code service/api/} package (task 4.3) and the
 * user module falls back to "nobody through this rule" while no implementation is present.
 */
public interface WorkerPhotoAccessPort {

  /**
   * True when {@code viewerUserId} holds "add and edit employees" in a shop where {@code
   * ownerUserId} has a join or link request that is Pending, or Rejected within the last 90 days.
   */
  boolean mayViewRequestPhoto(String viewerUserId, String ownerUserId);
}
