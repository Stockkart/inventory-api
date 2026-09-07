package com.inventory.common.tax;

/**
 * Whether the amounts on a supplier's bill already contain GST.
 *
 * <p>Both conventions are in daily use. A distributor who quotes an ex-GST rate states the taxable
 * value and lists the tax separately; one who bills at MRP states an amount the tax is already
 * inside. Reading a bill under the wrong convention moves the taxable value by the rate — roughly
 * 5% on pharma — in whichever direction the mistake runs, so it is recorded rather than guessed.
 */
public enum PurchaseTaxTreatment {

  /** Line amounts are net of tax; tax is added to reach the invoice total. */
  EXCLUSIVE,

  /** Line amounts already contain tax; the taxable value is backed out of them. */
  INCLUSIVE;

  /**
   * The convention assumed when a bill does not say.
   *
   * <p>Exclusive, because that is what every path in this codebase assumed before the distinction
   * was recorded at all. A document written under the old assumption therefore keeps reporting the
   * figure it has always reported, and only a bill explicitly marked inclusive changes.
   */
  public static PurchaseTaxTreatment orDefault(PurchaseTaxTreatment treatment) {
    return treatment == null ? EXCLUSIVE : treatment;
  }
}
