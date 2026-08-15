package com.inventory.app.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "grafana.cloud")
public class GrafanaCloudProperties {

  private String apiToken = "";
  private final Otlp otlp = new Otlp();
  private final Loki loki = new Loki();

  public String getApiToken() {
    return apiToken;
  }

  public void setApiToken(String apiToken) {
    this.apiToken = apiToken == null ? "" : apiToken;
  }

  public Otlp getOtlp() {
    return otlp;
  }

  public Loki getLoki() {
    return loki;
  }

  public static class Otlp {
    private String url = "";
    private String user = "";

    public String getUrl() {
      return url;
    }

    public void setUrl(String url) {
      this.url = url == null ? "" : url;
    }

    public String getUser() {
      return user;
    }

    public void setUser(String user) {
      this.user = user == null ? "" : user;
    }
  }

  public static class Loki {
    private String url = "";
    private String user = "";

    public String getUrl() {
      return url;
    }

    public void setUrl(String url) {
      this.url = url == null ? "" : url;
    }

    public String getUser() {
      return user;
    }

    public void setUser(String user) {
      this.user = user == null ? "" : user;
    }
  }
}
