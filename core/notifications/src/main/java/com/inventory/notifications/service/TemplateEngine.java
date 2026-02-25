package com.inventory.notifications.service;

import java.util.Map;

/**
 * Renders notification templates with variable substitution.
 */
public interface TemplateEngine {

  /**
   * Load template by id and inject variables.
   *
   * @param templateId template identifier
   * @param variables  map of variable name to value
   * @return rendered content
   */
  String render(String templateId, Map<String, Object> variables);

  /**
   * Render subject for email templates. Uses "subject" variable if present, else truncated body.
   */
  default String renderSubject(String templateId, Map<String, Object> variables) {
    if (variables != null && variables.containsKey("subject")) {
      Object sub = variables.get("subject");
      return sub != null ? sub.toString() : "";
    }
    String body = render(templateId, variables);
    return body != null && body.length() > 50 ? body.substring(0, 50) + "..." : (body != null ? body : "");
  }
}
