package com.socialapp.common.config;

import java.io.File;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Pins Tomcat's base and document-root directories under the project directory instead of
 * letting it fall back to {@code java.io.tmpdir}, which some Windows setups (stale Gradle
 * daemons, IDE run configurations) resolve to a non-writable path like {@code C:\WINDOWS\}.
 */
@Configuration
public class TomcatConfig {

  @Bean
  public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatDirectoriesCustomizer() {
    return factory -> {
      File baseDir = new File(".tomcat-temp");
      File docBase = new File(baseDir, "docbase");
      docBase.mkdirs();

      factory.setBaseDirectory(baseDir);
      factory.setDocumentRoot(docBase);
    };
  }
}
