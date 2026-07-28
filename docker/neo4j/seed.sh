#!/usr/bin/env bash
# Applies the friendship graph to Neo4j on every `docker compose up`.
#
# Friendship reads come from Neo4j while Flyway only manages Postgres, so without this nothing
# ever creates the graph and a fresh checkout shows an empty friends list and empty suggestions.
#
# Why a retry loop and not just depends_on/healthcheck: Neo4j reports healthy as soon as it
# answers on localhost, but on a first boot it initialises the store and rebinds the Bolt
# connector afterwards, so a container that connects the instant the healthcheck flips gets
# "Connection refused". Measured: the healthcheck passed and this script still failed on the
# first attempt. Waiting for a query to succeed from *this* container is the only honest check.
set -euo pipefail

NEO4J_ADDRESS="${NEO4J_ADDRESS:-neo4j://neo4j:7687}"
NEO4J_USER="${NEO4J_USER:-neo4j}"
NEO4J_PASSWORD="${NEO4J_PASSWORD:-neo4j_password}"
SEED_DIR="${SEED_DIR:-/seed}"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-60}"

run_cypher() {
  cypher-shell -a "$NEO4J_ADDRESS" -u "$NEO4J_USER" -p "$NEO4J_PASSWORD" "$@"
}

echo "neo4j-seed: waiting for Bolt at $NEO4J_ADDRESS"
for attempt in $(seq 1 "$MAX_ATTEMPTS"); do
  if run_cypher "RETURN 1;" >/dev/null 2>&1; then
    echo "neo4j-seed: connected after ${attempt} attempt(s)"
    break
  fi
  if [ "$attempt" -eq "$MAX_ATTEMPTS" ]; then
    echo "neo4j-seed: gave up after ${MAX_ATTEMPTS} attempts" >&2
    exit 1
  fi
  sleep 2
done

# Order matters only in that friend-graph.cypher creates the 25 User nodes the second file
# extends. Both are MERGE-only, so re-running on every `up` is a no-op once the graph exists.
for script in friend-graph.cypher friend-graph-nguyen-truc.cypher; do
  echo "neo4j-seed: applying $script"
  run_cypher -f "$SEED_DIR/$script"
done

echo "neo4j-seed: done"
