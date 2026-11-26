package com.inventory.hr.service;

import com.inventory.hr.domain.model.Employee;

/**
 * Ends the WORKER membership that belongs to an exited employee (worker-app R5.2, R5.3; design
 * DD14). {@link EmployeeService} calls {@link #onExit} right after a save that leaves the employee
 * {@code EXITED} with an exit date today or earlier (Asia/Kolkata), so the membership ends within
 * the save; {@code WorkerExitJob} calls it on its tick for exits whose date has since arrived and
 * for saves whose call here failed. Implementations must therefore be idempotent: a conditional
 * update on {@code membershipEndedAt == null} is the ledger.
 *
 * <p>Only a WORKER account's membership is ended; a staff account (OWNER, ADMIN, MANAGER, CASHIER)
 * keeps its staff membership when its linked employee exits (R13.4). {@code Employee.userId} is
 * never cleared, so payslips stay reachable from My HR.
 *
 * <p>Wired as an optional bean: without an implementation {@link #NONE} is used and an exit save
 * behaves exactly as before the Stockkart Team app existed.
 */
public interface WorkerMembershipEnder {

  /** Does nothing; the default when no implementation is on the context. */
  WorkerMembershipEnder NONE = employee -> { };

  /**
   * Ends the linked WORKER account's membership for this exit, once.
   *
   * @param employee the saved employee, {@code EXITED} with {@code exitDate <= today}
   */
  void onExit(Employee employee);
}
