// Seed a friendship graph for the 25 test accounts created in
// V29__seed_test_users_with_backgrounds.sql (userIds 9001-9025), so
// GET /v1/api/friendships/suggestions has real friends-of-friends data to rank.
//
// Flyway only manages Postgres, so this has to be applied to Neo4j separately. Run it after
// the app has applied V29, e.g.:
//   docker exec -i neo4j cypher-shell -u neo4j -p neo4j_password < docker/neo4j/seed/friend-graph.cypher
//
// Clusters (by primaryRole, matches V29):
//   Backend  9001-9005   Frontend 9006-9010   DevOps 9011-9014
//   Data/ML  9015-9018   Mobile   9019-9022   QA     9023-9025
//
// Every user ends up with exactly 4 friends. Backend and Frontend are intentionally NOT a full
// 5-clique internally (triangle + pair + one intra-cluster bridge instead), so some same-job
// pairs share a mutual friend without already being direct friends -- e.g. 9001 & 9004 (mutual:
// 9003), 9002 & 9004 (mutual: 9003), 9006 & 9009 (mutual: 9008), 9007 & 9009 (mutual: 9008).
// Call /suggestions as one of those users to confirm the background-based ranking actually
// promotes the same-job candidate over cross-job candidates with more mutual friends.

UNWIND [9001,9002,9003,9004,9005,9006,9007,9008,9009,9010,9011,9012,9013,9014,9015,9016,9017,9018,9019,9020,9021,9022,9023,9024,9025] AS uid
MERGE (:User {userId: uid});

// --- Backend cluster: triangle (9001-9003) + pair (9004-9005) + 1 bridge (9003-9004) ---
MATCH (a:User {userId: 9001}), (b:User {userId: 9002}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9002}), (b:User {userId: 9003}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9003}), (b:User {userId: 9001}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9004}), (b:User {userId: 9005}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9003}), (b:User {userId: 9004}) MERGE (a)-[:FRIENDS_WITH]-(b);

// --- Frontend cluster: triangle (9006-9008) + pair (9009-9010) + 1 bridge (9008-9009) ---
MATCH (a:User {userId: 9006}), (b:User {userId: 9007}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9007}), (b:User {userId: 9008}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9008}), (b:User {userId: 9006}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9009}), (b:User {userId: 9010}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9008}), (b:User {userId: 9009}) MERGE (a)-[:FRIENDS_WITH]-(b);

// --- DevOps cluster: full mesh ---
MATCH (a:User {userId: 9011}), (b:User {userId: 9012}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9011}), (b:User {userId: 9013}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9011}), (b:User {userId: 9014}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9012}), (b:User {userId: 9013}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9012}), (b:User {userId: 9014}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9013}), (b:User {userId: 9014}) MERGE (a)-[:FRIENDS_WITH]-(b);

// --- Data/ML cluster: full mesh ---
MATCH (a:User {userId: 9015}), (b:User {userId: 9016}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9015}), (b:User {userId: 9017}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9015}), (b:User {userId: 9018}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9016}), (b:User {userId: 9017}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9016}), (b:User {userId: 9018}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9017}), (b:User {userId: 9018}) MERGE (a)-[:FRIENDS_WITH]-(b);

// --- Mobile cluster: full mesh ---
MATCH (a:User {userId: 9019}), (b:User {userId: 9020}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9019}), (b:User {userId: 9021}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9019}), (b:User {userId: 9022}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9020}), (b:User {userId: 9021}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9020}), (b:User {userId: 9022}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9021}), (b:User {userId: 9022}) MERGE (a)-[:FRIENDS_WITH]-(b);

// --- QA cluster: full mesh ---
MATCH (a:User {userId: 9023}), (b:User {userId: 9024}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9023}), (b:User {userId: 9025}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9024}), (b:User {userId: 9025}) MERGE (a)-[:FRIENDS_WITH]-(b);

// --- Cross-cluster bridges: fill remaining slots up to 4 friends + create cross-job candidates ---
MATCH (a:User {userId: 9001}), (b:User {userId: 9011}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9002}), (b:User {userId: 9015}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9004}), (b:User {userId: 9019}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9005}), (b:User {userId: 9023}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9005}), (b:User {userId: 9012}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9005}), (b:User {userId: 9016}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9006}), (b:User {userId: 9013}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9007}), (b:User {userId: 9017}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9009}), (b:User {userId: 9020}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9010}), (b:User {userId: 9024}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9010}), (b:User {userId: 9014}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9010}), (b:User {userId: 9018}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9021}), (b:User {userId: 9025}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9022}), (b:User {userId: 9023}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9024}), (b:User {userId: 9003}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9025}), (b:User {userId: 9008}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9001}), (b:User {userId: 9006}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9002}), (b:User {userId: 9007}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9004}), (b:User {userId: 9009}) MERGE (a)-[:FRIENDS_WITH]-(b);
