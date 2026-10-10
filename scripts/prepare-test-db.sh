#!/bin/sh
# Prepares the database that the integration tests run against: a throwaway one-node MongoDB replica set
# (transactions need it) with the database that multi-tour-identity-audit-db builds, applied by the migration
# runner of that repository. This repository makes no schema (norm 5.2.1), so the tests are not written against
# a shape the real database never has.
#
#   sh scripts/prepare-test-db.sh up     prints TEST_DATABASE_URL on stdout, everything else goes to stderr
#   sh scripts/prepare-test-db.sh down   removes the container
#
# The db repository is taken from DB_REPO (a sibling checkout, ../multi-tour-identity-audit-db by default) or,
# when it is not there, cloned from DB_REPO_URL at DB_REPO_REF. Docker and git are the only requirements; the CI
# and a developer run this same file. Nothing is written to disk but the clone, and no secret is versioned: the
# password of the application user is made on the spot and thrown away with the container.
set -eu
cd "$(dirname "$0")/.."

# Git Bash on Windows rewrites container paths; no effect elsewhere
export MSYS_NO_PATHCONV=1

NAME="${TEST_DB_NAME:-idaapi-testdb}"
PORT="${TEST_DB_PORT:-27017}"
# Same versions from another registry when Docker Hub limits the address (the CI sets both; see ci.yml)
MONGO_IMAGE="${MONGO_IMAGE:-mongo:8.0.32}"
LIQUIBASE_BASE="${LIQUIBASE_BASE:-liquibase/liquibase:5.0.4}"
DB_REPO="${DB_REPO:-../multi-tour-identity-audit-db}"
DB_REPO_URL="${DB_REPO_URL:-https://github.com/code-corhuila/multi-tour-identity-audit-db.git}"
DB_REPO_REF="${DB_REPO_REF:-develop}"

log() { echo "$@" >&2; }

# Docker Hub and the registries can answer 429 to a shared address: wait and try again. retry ATTEMPTS SECONDS COMMAND...
retry() {
  attempts=$1; wait=$2; shift 2
  tried=1
  until "$@"; do
    [ "$tried" -ge "$attempts" ] && return 1
    log "failed (attempt $tried of $attempts), waiting ${wait}s"
    tried=$((tried + 1)); sleep "$wait"
  done
}

case "${1:-}" in
  down)
    docker rm -fv "$NAME" >/dev/null 2>&1 || true
    exit 0
    ;;
  up) ;;
  *)
    log "usage: sh scripts/prepare-test-db.sh up|down"
    exit 2
    ;;
esac

if [ ! -f "$DB_REPO/changelog/changelog-master.yaml" ]; then
  log "cloning $DB_REPO_URL ($DB_REPO_REF)"
  clone=$(mktemp -d)
  git clone --quiet --depth 1 --branch "$DB_REPO_REF" "$DB_REPO_URL" "$clone/db" >&2
  DB_REPO="$clone/db"
fi
DB_REPO=$(cd "$DB_REPO" && pwd)

docker rm -fv "$NAME" >/dev/null 2>&1 || true
retry 3 20 docker pull -q "$MONGO_IMAGE" >/dev/null || { log "the MongoDB image could not be downloaded"; exit 1; }
# The member is named localhost:PORT, as the host and the runner (which shares the network of the container) both see it
docker run -d --name "$NAME" -p "127.0.0.1:$PORT:$PORT" "$MONGO_IMAGE" --replSet rs0 --bind_ip_all --port "$PORT" >/dev/null

ready=0
for _ in $(seq 1 60); do
  if docker exec "$NAME" mongosh --quiet --port "$PORT" --eval "db.hello().ok" >/dev/null 2>&1; then ready=1; break; fi
  sleep 1
done
[ "$ready" = 1 ] || { log "the throwaway MongoDB did not start"; exit 1; }
docker exec "$NAME" mongosh --quiet --port "$PORT" --eval "rs.initiate({_id: 'rs0', members: [{_id: 0, host: 'localhost:$PORT'}]})" >/dev/null
primary=0
for _ in $(seq 1 60); do
  if [ "$(docker exec "$NAME" mongosh --quiet --port "$PORT" --eval "db.hello().isWritablePrimary" 2>/dev/null)" = "true" ]; then primary=1; break; fi
  sleep 1
done
[ "$primary" = 1 ] || { log "the replica set never had a primary"; exit 1; }

# What multi-tour-infra-mongo leaves for the domain: the user of the connection, whose roles the migration grants
docker exec "$NAME" mongosh --quiet --port "$PORT" identity_audit \
  --eval "db.createUser({user: 'identity_audit_app', pwd: crypto.randomUUID(), roles: [{role: 'readWrite', db: 'identity_audit'}]})" >/dev/null

LIQUIBASE_IMAGE="$NAME-liquibase"
retry 3 20 docker build -q -f "$DB_REPO/deploy/liquibase.Dockerfile" --build-arg LIQUIBASE_BASE="$LIQUIBASE_BASE" \
  -t "$LIQUIBASE_IMAGE" "$DB_REPO/deploy" >/dev/null || { log "the runner image did not build"; exit 1; }
docker run --rm --network "container:$NAME" -v "$DB_REPO:/workspace:ro" "$LIQUIBASE_IMAGE" \
  --search-path=/workspace --changelog-file=changelog/changelog-master.yaml \
  --url="mongodb://localhost:$PORT/identity_audit?directConnection=true" \
  --database-changelog-table-name=databasechangelog_identity_audit \
  --database-changelog-lock-table-name=databasechangeloglock_identity_audit update >&2 \
  || { log "the migrations of the db repository did not apply"; exit 1; }
docker rmi "$LIQUIBASE_IMAGE" >/dev/null 2>&1 || true

echo "mongodb://localhost:$PORT/identity_audit?replicaSet=rs0"
