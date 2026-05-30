package com.socialapp.friendships.entity;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Node("User")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserNode {
  @Id @GeneratedValue private Long id;

  @Property private Integer userId;
}
