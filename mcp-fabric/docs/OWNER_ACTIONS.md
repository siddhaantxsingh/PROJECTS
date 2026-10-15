# Owner actions — MCP Fabric

These steps need things only the owner has: a real identity provider, a domain and certificate, and an idle machine. Nothing here has been run by the agent. Save the outputs listed under each step in `docs/evidence/<date>/`, and then update `requirements.md`.

## 1. Connect a real IdP: Keycloak (local, Docker)

```sh
docker run -d --name kc -p 8081:8080 -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:26.0 start-dev
```

In the admin console (http://localhost:8081, admin/admin):

1. Create realm `mcp`.
2. **Client scopes.** Create the client scopes `mcp:tools`, `mcp:admin` and `mcp:server:files`. Set the type to *Optional* and turn on "Include in token scope".
3. **Audience mapper.** In client scope `mcp:tools`, add a mapper: *By configuration → Audience*, "Included Custom Audience" = `https://mcp.example.com/mcp`, "Add to access token" ON. This puts the resource URI into `aud`. Use your real gateway URL.
4. **Tenant mapper.** Add a second mapper: *User Attribute*, user attribute `tenant`, token claim name `tenant`, claim JSON type String, "Add to access token" ON.
5. **Client.** Create client `mcp-cli`: *OpenID Connect*, client authentication ON, service accounts ON. Under *Client scopes*, add `mcp:tools` and `mcp:server:files` as Optional.
6. **Tenant attribute.** Under *Service account roles → user `service-account-mcp-cli` → Attributes*, set `tenant` = `acme`. On Keycloak 24 and later, first enable *Unmanaged attributes* in the realm settings.

Configure the gateway (from `config/oauth-example.yml`):

```yaml
fabric.auth:
  issuer: http://localhost:8081/realms/mcp
  jwks-uri: http://localhost:8081/realms/mcp/protocol/openid-connect/certs
  resource: https://mcp.example.com/mcp
```

Get a token and call the gateway:

```sh
TOKEN=$(curl -s -d grant_type=client_credentials -d client_id=mcp-cli -d client_secret=<secret> \
  -d 'scope=mcp:tools mcp:server:files' http://localhost:8081/realms/mcp/protocol/openid-connect/token | jq -r .access_token)
echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | jq '{iss,aud,scope,tenant,exp}'
curl -sk -i https://localhost:8443/mcp -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
curl -sk https://localhost:8443/.well-known/oauth-protected-resource/mcp | jq .
```

**Expected:**

* The decoded token has `iss` equal to the issuer, `aud` containing `https://mcp.example.com/mcp`, a `scope` that includes `mcp:tools`, and `tenant: "acme"`.
* `initialize` returns 200 with an `Mcp-Session-Id` header.
* Without the header you get 401 and `WWW-Authenticate: Bearer realm="mcp-fabric", resource_metadata="https://mcp.example.com/.well-known/oauth-protected-resource/mcp"`.

**Save:** the decoded claims (not the token), the three HTTP responses, and the gateway log.

**Key rotation check:** in *Realm settings → Keys*, add a new RSA provider with a higher priority. Get a new token and check that it is accepted without restarting the gateway.

## 2. Auth0 (alternative)

1. *Applications → APIs → Create API*: identifier `https://mcp.example.com/mcp`. This becomes `aud`. Use signing algorithm RS256.
2. Under *Permissions*, add `mcp:tools`, `mcp:admin` and `mcp:server:files`.
3. Create a *Machine to Machine* application authorised for that API with the scopes it needs.
4. Add a *Post-Login* / *Credentials Exchange* Action that sets the tenant claim:
   `api.accessToken.setCustomClaim("tenant", "acme")`.
   Auth0 requires custom claims in the access token to be namespaced, or to use non-reserved names. If you use a namespaced claim, set `fabric.auth.tenant-claim` to the full claim name.
5. Configure the gateway:
   * `issuer: https://<tenant>.auth0.com/` (note the trailing slash);
   * `jwks-uri: https://<tenant>.auth0.com/.well-known/jwks.json`;
   * `resource: https://mcp.example.com/mcp`.
6. Get a token with `POST https://<tenant>.auth0.com/oauth/token`, `grant_type=client_credentials` and `audience=https://mcp.example.com/mcp`. The scopes are returned in `scope`.

Expected results and what to save are the same as for Keycloak.

## 3. TLS certificate

For testing, a self-signed certificate:

```sh
keytool -genkeypair -alias fabric -keyalg EC -groupname secp256r1 -validity 365 -dname CN=mcp.example.com \
  -ext SAN=dns:mcp.example.com -storetype PKCS12 -keystore data/fabric-tls.p12 -storepass "$FABRIC_TLS_PASSWORD"
```

For production, a real certificate, for example from Let's Encrypt:

```sh
openssl pkcs12 -export -in fullchain.pem -inkey privkey.pem -name fabric -out data/fabric-tls.p12 -passout env:FABRIC_TLS_PASSWORD
```

Then start the gateway with `config/oauth-example.yml`, which has `server.ssl.*` set, and check:

```sh
openssl s_client -connect mcp.example.com:8443 -servername mcp.example.com </dev/null 2>/dev/null | grep -E 'Protocol|Verify return'
```

Expected: `Protocol: TLSv1.3` and `Verify return code: 0 (ok)`. Save that output.

## 4. Clean benchmark

On an idle machine (1-minute load average below 1), run:

```sh
for i in 1 2 3; do uptime; mvn -B -q test -Dtest=BenchmarkTest -Dbench=true; cp target/bench.json bench-$i.json; done
```

Do this for both commit `4fe1ec7` (before the security changes) and the current commit. Then replace the inconclusive table in `benchmarks.md`.

## 5. Secrets

* Upstream credentials go in the gateway's environment, for example `SEARCH_UPSTREAM_TOKEN` and `FILES_UPSTREAM_TOKEN`, referenced from YAML as `${...}`. Never put them in the YAML file.
* Keep IdP access-token lifetimes short (5–15 min). The gateway does not do introspection, so a revoked token stays valid until it expires.
