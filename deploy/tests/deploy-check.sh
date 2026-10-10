#!/bin/sh
# I·api_image_builds_starts_answers_and_is_not_published (task API-04 of the spec of HU-IDA-001)
#
# Builds the image, checks that the compose file publishes no port to the host (only the gateway may, norm
# 5.6.1) and has no database service or volume (Annex J, J.7), starts the image with a public key made here
# and asks it for /health and for a protected route. The key is made on the spot and thrown away with the
# container. Docker and openssl are the only requirements; the CI and a developer run this same file.
set -u
cd "$(dirname "$0")/../.." || exit 1

# Git Bash on Windows rewrites container paths; no effect elsewhere
export MSYS_NO_PATHCONV=1

# Same versions from another registry when Docker Hub limits the address (the CI sets both; see ci.yml)
MAVEN_IMAGE="${MAVEN_IMAGE:-maven:3.9.9-eclipse-temurin-21}"
JRE_IMAGE="${JRE_IMAGE:-eclipse-temurin:21.0.8_9-jre}"
RUN_ID="idaapi-deploy-$$"
fail=0

cleanup() {
  docker rm -f "$RUN_ID" >/dev/null 2>&1 || true
  docker rmi "$RUN_ID" >/dev/null 2>&1 || true
  rm -f "$KEYFILE"
}
KEYFILE=$(mktemp)
trap cleanup EXIT
trap 'exit 1' INT TERM

check() { # name, then the command that must succeed
  name=$1; shift
  if reason=$("$@" 2>&1); then echo "PASS  $name"; else echo "FAIL  $name"; printf '%s\n' "$reason" | tail -n 8 | sed 's/^/        /'; fail=1; fi
}

build_image() {
  docker build -q -f deploy/Dockerfile --build-arg MAVEN_IMAGE="$MAVEN_IMAGE" --build-arg JRE_IMAGE="$JRE_IMAGE" -t "$RUN_ID" .
}

compose_has_no_published_port_database_or_volume() {
  out=$(JWT_PUBLIC_KEY=x docker compose -f deploy/compose.yml config) || { echo "$out"; return 1; }
  echo "$out" | grep -q '^ *ports:' && { echo "the file publishes a port"; return 1; }
  echo "$out" | grep -q '8082' || { echo "the file exposes no 8082"; return 1; }
  echo "$out" | grep -qi 'mongo:\|postgres:\|^volumes:' && { echo "the file defines a database or a volume"; return 1; }
  return 0
}

# A one-line public key, as the service reads it from JWT_PUBLIC_KEY
make_key() {
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null | openssl rsa -pubout 2>/dev/null \
    | awk 'NF {printf "%s\n", $0}' > "$KEYFILE"
  [ -s "$KEYFILE" ]
}

starts_and_answers() {
  make_key || { echo "could not make a key"; return 1; }
  docker run -d --name "$RUN_ID" -p 127.0.0.1::8082 -e PORT=8082 -e JWT_PUBLIC_KEY="$(cat "$KEYFILE")" "$RUN_ID" >/dev/null || return 1
  port=$(docker port "$RUN_ID" 8082/tcp | head -n 1 | sed 's/.*://')
  health=000
  for _ in $(seq 1 40); do
    health=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/health" || true)
    [ "$health" = 200 ] && break
    sleep 1
  done
  [ "$health" = 200 ] || { echo "/health answered $health"; docker logs "$RUN_ID" 2>&1 | tail -n 5; return 1; }
  protected=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/api/v1/identity-audit/tenants")
  [ "$protected" = 401 ] || { echo "a protected route without a token answered $protected"; return 1; }
}

check "I·api_image_builds" build_image
check "I·api_compose_publishes_no_port_and_defines_no_database" compose_has_no_published_port_database_or_volume
check "I·api_image_starts_answers_health_and_protects_the_api" starts_and_answers

[ "$fail" = 0 ] && echo "ALL CHECKS PASSED" || echo "SOME CHECKS FAILED"
exit "$fail"
