package com.socialapp;

import static org.mockito.Mockito.mock;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests. Containers are started once in a static initializer and
 * reused (never stopped) across every subclass in the JVM run — the standard Testcontainers
 * "singleton container" pattern — so each test class doesn't pay container startup cost again.
 * Testcontainers' Ryuk reaper tears them down when the JVM exits.
 */
@SpringBootTest(
    classes = SocialApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
          .withDatabaseName("socialapp")
          .withUsername("postgres")
          .withPassword("postgres");

  protected static final Neo4jContainer<?> NEO4J =
      new Neo4jContainer<>(DockerImageName.parse("neo4j:5-community"))
          .withAdminPassword("neo4j_password");

  protected static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  protected static final MinIOContainer MINIO =
      new MinIOContainer(DockerImageName.parse("minio/minio:RELEASE.2024-01-16T16-07-38Z"))
          .withUserName("minio_admin")
          .withPassword("minio_admin_password");

  static {
    POSTGRES.start();
    NEO4J.start();
    REDIS.start();
    MINIO.start();
  }

  @DynamicPropertySource
  static void registerDynamicProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);

    registry.add("spring.neo4j.uri", NEO4J::getBoltUrl);
    registry.add("spring.neo4j.authentication.username", () -> "neo4j");
    registry.add("spring.neo4j.authentication.password", NEO4J::getAdminPassword);

    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

    registry.add("minio.url", MINIO::getS3URL);
    registry.add("minio.access-key", MINIO::getUserName);
    registry.add("minio.secret-key", MINIO::getPassword);
  }

  @TestConfiguration
  static class MailSenderTestConfiguration {

    /** Replaces the real JavaMailSender with a Mockito mock so tests never open an SMTP connection. */
    @Bean
    JavaMailSender javaMailSender() {
      return mock(JavaMailSender.class);
    }
  }
}
