package com.inventory.notifications.template;

import com.inventory.notifications.domain.model.WhatsAppTemplate;

import java.util.Map;

/**
 * Provides plain-text body for WhatsApp templates. Variables use {{name}} placeholders.
 */
public final class WhatsAppTemplateContent {

  private WhatsAppTemplateContent() {}

  /**
   * Returns the message body for a WhatsApp template. Variables: userName, userEmail, etc.
   */
  public static String getBody(WhatsAppTemplate template, Map<String, Object> variables) {
    return switch (template) {
      case LOGIN_NOTIFICATION -> "New login to your Stock Kart account ({{userEmail}}). "
          + "Hi {{userName}}, if this wasn't you, please change your password immediately. - Stock Kart";
    };
  }
}
