package com.socialapp.friendships.config;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.core.transaction.Neo4jTransactionManager;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;

import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
// Bổ sung annotation này để trỏ Neo4j Repository dùng đúng Transaction Manager của nó
@EnableNeo4jRepositories(
    basePackages = "com.socialapp.friendships.repository",
    transactionManagerRef = "neo4jTransactionManager")
public class Neo4jConfig {

  // Khởi tạo thủ công Transaction Manager riêng cho Neo4j
  @Bean(name = "neo4jTransactionManager")
  public Neo4jTransactionManager neo4jTransactionManager(Driver driver) {
    return new Neo4jTransactionManager(driver);
  }

  @Bean
  CommandLineRunner initNeo4jSchema(Driver driver) {
    return args -> {
      try (Session session = driver.session()) {
        session.run(
            """
              CREATE CONSTRAINT user_userId_unique IF NOT EXISTS
              FOR (u:User) REQUIRE u.userId IS UNIQUE
            """);
        log.info("Neo4j schema initialized: constraint user_userId_unique ensured");
      } catch (Exception e) {
        log.warn("Neo4j schema initialization failed: {}", e.getMessage());
      }
    };
  }
}
