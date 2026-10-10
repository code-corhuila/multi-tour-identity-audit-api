# Interoperability fixture

`service-token.jwt` is the token of a service as `multi-tour-security` issues it (`security issue-service-token`: RS256,
`iss` `multi-tour-security`, `aud` `multi-tour`, `sub` `worker-event-relay`, role `SERVICE`, no tenant), signed by the Go
signer of that service with a key made for this test and thrown away; `security-public-key.pub` is its public half. Only
the end of its life was moved to the year 2100, so the fixture does not expire under the tests. There is no private key
here, and nothing that signs.

Test only: this public key is never a deployment key. Do not put it in any `JWT_PUBLIC_KEY` of a real environment.
