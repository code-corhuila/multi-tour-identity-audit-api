# multi-tour-identity-audit-api

> The service of the identity-audit domain: the tenant lifecycle and, later, the audit records

Part of the **Multi Tour** distributed system — team `multi-tour`, Grupo 2.
Governance and documentation live in [`multi-tour-docs`](https://github.com/code-corhuila/multi-tour-docs).

## What it is

A Java 21 / Spring Boot 3.5 service in three Maven modules: `core` (the domain and its ports, no framework), `adapters` (HTTP and MongoDB) and `app` (the composition root). It owns the tenants of the platform and the events they leave. Its database, `identity_audit`, is built by `multi-tour-identity-audit-db`; this repository holds no schema or migration.

## How to start it

The image is built and run by the composition of the platform: `multi-tour-infra-postgres` includes `deploy/compose.yml`, which defines the service `identity-audit-api` on the network `platform`. It exposes 8082 and publishes no port: only the gateway is reachable from the host. It needs `JWT_PUBLIC_KEY` (or `JWT_PUBLIC_KEY_FILE`), the public key of the security microservice; without it the service stops at start and says so. For a development key, `scripts/dev-keys.sh` of the infrastructure makes one. To try the image alone, `sh deploy/tests/deploy-check.sh` builds it, starts it with a key made on the spot and asks it for `/health` and for a protected route; it needs Docker and openssl.

## Where the data is

In the database `identity_audit` of the MongoDB instance of `multi-tour-infra-mongo`, with the user `identity_audit_app`.

## How to test it

`mvn -B verify` with Java 21, and `sh deploy/tests/deploy-check.sh` for the image. Both run on every pull request to `develop` and `qa` (`ci.yml`).

## What is missing

The tenant lifecycle itself (the routes, the persistence, the events): see `07-api/specs/identity-audit/multi-tour-identity-audit-api/` in `multi-tour-docs`.

## Branching

Three permanent branches. **None of them accepts a direct commit** — you enter through a child
branch and leave through a Pull Request.

```
develop  <--PR--  feat/... fix/... chore/...
qa       <--PR--  qa/...
main     <--PR--  release/...  hotfix/...
```

Promotion happens **by re-application** (`git cherry-pick -x`), never by merging one permanent
branch into another: `merge develop -> qa` and `merge qa -> main` do not exist in this model.

`main` requires **1 approval from `ariel5253`**. On `develop` and `qa` the team sets its own review
rule.

Full policy: `00-governance/branching-policy.md` in `multi-tour-docs`.
