package com.socialapp.friendships.config;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
public class Neo4jConfig {
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
