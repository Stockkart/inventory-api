package com.inventory.notifications.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple template engine using {{variable}} placeholders.
 * Can be replaced with a more sophisticated engine (Thymeleaf, FreeMarker).
 */
@Slf4j
@Component
public class DefaultTemplateEngine implements TemplateEngine {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^}]+)}\\}");

  @Override
  public String render(String templateId, Map<String, Object> variables) {
    if (variables == null || variables.isEmpty()) {
      return "";
    }
    // For templateId, we use it as the base content key or load from a store.
    // Simplified: treat templateId as fallback and substitute into a generic format.
    String content = resolveTemplateContent(templateId, variables);
    return substituteVariables(content, variables);
  }

  private String resolveTemplateContent(String templateId, Map<String, Object> variables) {
    // Placeholder: in production, load from DB/files. For now return first variable value or templateId.
    Object body = variables.get("body");
    if (body != null) {
      return body.toString();
    }
    Object message = variables.get("message");
    if (message != null) {
      return message.toString();
    }
    return templateId;
  }

  private String substituteVariables(String content, Map<String, Object> variables) {
    if (content == null) {
      return "";
    }
    Matcher m = PLACEHOLDER.matcher(content);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      String key = m.group(1).trim();
      Object val = variables.get(key);
      m.appendReplacement(sb, Matcher.quoteReplacement(val != null ? val.toString() : ""));
    }
    m.appendTail(sb);
    return sb.toString();
  }
}
