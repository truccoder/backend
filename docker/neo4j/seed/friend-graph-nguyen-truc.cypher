// Extends the friend graph from friend-graph.cypher so nguyen.truc@test.com (userId 9001,
// Backend Developer) ends up friends with exactly:
//   - 2 people sharing the same job title (Backend): 9002, 9003
//   - 2 people from every OTHER job title: Frontend (9006, 9007), DevOps (9011, 9012),
//     Data Scientist (9015, 9016), Mobile Developer (9019, 9020), QA Engineer (9023, 9024)
//
// All statements are MERGE, so this is safe to run standalone even though 9001-9002, 9001-9003,
// 9001-9006, and 9001-9011 already exist from friend-graph.cypher — those four are no-ops here.
//
// Run after V30 (and after friend-graph.cypher, though order relative to it doesn't matter):
//   docker exec -i neo4j cypher-shell -u neo4j -p neo4j_password < docker/neo4j/seed/friend-graph-nguyen-truc.cypher

MATCH (a:User {userId: 9001}), (b:User {userId: 9002}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9001}), (b:User {userId: 9003}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9001}), (b:User {userId: 9006}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9001}), (b:User {userId: 9007}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9001}), (b:User {userId: 9011}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9001}), (b:User {userId: 9012}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9001}), (b:User {userId: 9015}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9001}), (b:User {userId: 9016}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9001}), (b:User {userId: 9019}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9001}), (b:User {userId: 9020}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9001}), (b:User {userId: 9023}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9001}), (b:User {userId: 9024}) MERGE (a)-[:FRIENDS_WITH]-(b);
