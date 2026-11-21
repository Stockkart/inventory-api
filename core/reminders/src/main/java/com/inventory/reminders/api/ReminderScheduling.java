package com.inventory.reminders.api;

/** Creates reminders on behalf of other modules. */
public interface ReminderScheduling {

  /**
   * Creates the expiry and custom reminders for a new stock lot. Failures are logged and
   * swallowed, so adding stock never fails because of a reminder.
   */
  void createReminderForInventoryCreate(InventoryReminderRequest request);
}
