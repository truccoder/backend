package com.socialapp.cloud.minio;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MinIOConfigTest {

  @Test
  @DisplayName("strips a trailing slash so getUrl() + \"/\" + bucket never doubles up")
  void stripsTrailingSlash() {
    MinIOConfig config = new MinIOConfig();

    config.setUrl("https://files.elitenexus.id.vn/");

    assertThat(config.getUrl()).isEqualTo("https://files.elitenexus.id.vn");
  }

  @Test
  @DisplayName("strips several trailing slashes")
  void stripsRepeatedTrailingSlashes() {
    MinIOConfig config = new MinIOConfig();

    config.setUrl("http://minio:9000///");

    assertThat(config.getUrl()).isEqualTo("http://minio:9000");
  }

  @Test
  @DisplayName("leaves a clean URL untouched")
  void leavesCleanUrlAlone() {
    MinIOConfig config = new MinIOConfig();

    config.setUrl("http://localhost:9000");

    assertThat(config.getUrl()).isEqualTo("http://localhost:9000");
  }

  @Test
  @DisplayName("tolerates a null URL")
  void toleratesNull() {
    MinIOConfig config = new MinIOConfig();

    config.setUrl(null);

    assertThat(config.getUrl()).isNull();
  }
}
