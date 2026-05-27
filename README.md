This is a Keycloak implementation of the [Transaction Tokens](https://datatracker.ietf.org/doc/draft-ietf-oauth-transaction-tokens/) Internet Draft.

You can try it out in local Kubernetes, using the [demo environment](https://github.com/CarrettiPro/tts-demo) provided. Alternatively, you can build and deploy the Transaction Token Service into your existing Keycloak installation.

## Overview

The draft introduces a concept of a Transaction Token (Txn-Token), which encapsulates the data related to the requesting user (human), requesting workload (non-human) and the transaction itself, and preserves it throughout the entire call chain:
> Transaction Tokens (Txn-Tokens) are designed to maintain and propagate
> user identity, workload identity and authorization context throughout
> the Call Chain within a trusted domain during the processing of external
> requests (e.g. such as API calls) or requests initiated internally
> within the trust domain. Txn-Tokens ensure that this context is
> preserved throughout the Call Chain thereby enhancing security and
> consistency in complex, multi-service architectures.

The draft also introduces Transaction Token Service (TTS), an OAuth 2.0 compliant service that issues Txn-Tokens via OAuth 2.0 Token Exchange. The diagram below shows the typical deployment and data flow for Transaction Tokens:
![overview](docs/img/overview.svg)

### Standard Flow
> [!NOTE]
> Legend:  
> AT = Access Token  
> TT = Transaction Token  
> RCTX = request context (user IP address, auth details…)  
> TCTX = transaction context (amount, …)

![sequence](docs/img/sequence.svg)

## Build

```
# Main JAR
mvn clean install

# Docker images
mvn docker:build
```

The main build will generate the provider JAR in `target`.

The Docker build will generate two images (`XXX` is Keycloak version, `YYY` is TTS version):
- `carretti/keycloak-tts:XXX-YYY` - Keycloak image with embedded TTS provider
- `carretti/keycloak-tts-init:XXX-YYY` - init container image for Kubernetes (provider JAR only)

## Deploy

### Standalone

Copy the `target/keycloak-tts-YYY.jar` into the `providers` directory and restart Keycloak.

### Docker

You can either use the image with embedded TTS provider:

```
docker run carretti/keycloak-tts:26.6.0-latest start
```

or mount the provider as a JAR into the `providers` directory of the official Keycloak image:

```
docker run -v $(pwd)/target/keycloak-tts-1.0.0-SNAPSHOT.jar:/opt/keycloak/providers/keycloak-tts.jar quay.io/keycloak/keycloak:26.6.0 start-dev
```

### Kubernetes

On Kubernetes, you can use init container image with your favorite Keycloak Helm chart.
  
Example use (with [Codecentric Keycloak.x](https://artifacthub.io/packages/helm/codecentric/keycloakx) chart):
```yaml
extraInitContainers: |
  - name: keycloak-tts
    image: carretti/keycloak-tts-init
    imagePullPolicy: IfNotPresent
    command:
      - sh
    args:
      - -c
      - |
        echo "Copying providers..."
        cp -R /tmp/providers/* /providers/
    volumeMounts:
      - name: providers
        mountPath: /providers
extraVolumeMounts: |
  - name: providers
    mountPath: /opt/keycloak/providers
extraVolumes: |
  - name: providers
    emptyDir: {}

```

## Configure

### Identity Provider
To enable Transaction Token Service, create an OpenID Connect Identity Provider and name it `tts`:
<img width="2190" height="3024" alt="TTS Identity Provider" src="https://github.com/user-attachments/assets/2c74b253-ab84-409e-b0a7-8546bfafe9c9" />

The name `tts` is currently hardcoded, and will be used by the implementation to resolve the identity provider. In the future, it will be possible to link an arbitrary identity provider via the Keycloak Admin UI.

### Transaction Token Audience
As per [12.1. Txn-Token Request](https://www.ietf.org/archive/id/draft-ietf-oauth-transaction-tokens-08.html#name-txn-token-request), the `audience` parameter is mandatory, and must be set to the Trust Domain name.

 In Keycloak TTS, this configuration option is also mandatory. Currently, it does not have a UI. To configure trust domain / allowed audience, please use the `kcadm` tool:
 ```
 $ bin/kcadm.sh config credentials --server ${KEYCLOAK_URL} --realm master --user ${KEYCLOAK_ADMIN_USERNAME} --password ${KEYCLOAK_ADMIN_PASSWORD}
 $ bin/kcadm.sh update -r ${TTS_REALM} identity-provider/instances/tts -s 'config."tts.audience"=example.org'
```

### Access Token Audience

The current implementation performs strict audience check on the external access token. The value of the `aud` claim must be equal to the `Client ID` value entered in the Identity Provider configuration screen.
(`Client Secret` is currently ignored and just needs to be non-empty.)

If your external IdP is Keycloak, you can achieve that using either `Audience Resolve` or `Audience` mappers.

## Examples

This is an example request for the Transaction Token Service that is sent to the token endpoint. The client ID is `tts-client`, it uses a secret to authenticate, and the `tts` Identity Provider is configured with the allowed audience `example.org`.

```
POST /realms/demo/protocol/openid-connect/token HTTP/1.1
Content-Type: application/x-www-form-urlencoded
Accept: application/json

grant_type=urn:ietf:params:oauth:grant-type:token-exchange&
client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-spiffe&
client_assertion=eyJhbGci[...redacted...].eyJhdWQi[...redacted...].Xlv5lW4c[...redacted...]&
subject_token_type=urn:ietf:params:oauth:token-type:access_token&
subject_token=eyJhbGci[...redacted...].eyJleHAi[...redacted...].GAEppxA6[...redacted...]&
requested_token_type=urn:ietf:params:oauth:token-type:txn_token&
audience=trust-domain.example&
scope=foo+bar&
request_context=%7B%22req_ip%22%3A%2269.151.72.123%22%2C%22authn%22%3A%22urn%3Aietf%3Arfc%3A6749%22%7D&
request_details=%7B%22action%22%3A%20%22BUY%22%2C%22ticker%22%3A%20%22MSFT%22%2C%22quantity%22%3A%20%22100%22%7D
```

Here, `request_context` is a JSON object describing the request-specific attributes:
```json
{
    "req_ip": "69.151.72.123", // env context of the external call
    "authn": "urn:ietf:rfc:6749", // env context of the external call
}
```

`request_details` is a JSON object describing the transaction properties:
```json
{
    "action": "BUY", // parameter of the external call
    "ticker": "MSFT", // parameter of the external call
    "quantity": "100" // parameter of the external call
}
```

The TTS returns a token response:
```
HTTP/1.1 200 OK
Content-Type: application/json

{
  "issued_token_type": "urn:ietf:params:oauth:token-type:txn_token",
  "access_token": "eyJhbGci[...redacted...].eyJleHAi[...redacted...].QL9TUdAb[...redacted...]",
  "token_type": "N_A"
}
```

The payload of the issued Txn-Token:
```json
{
  "iat": 1686536226,
  "exp": 1686536586,
  "aud": "trust-domain.example",
  "sub": "d084sdrt234fsaw34tr23t",
  "scope" : "trade.stocks",
  "txn": "97053963-771d-49cc-a4e3-20aad399c312",
  "req_wl": "spiffe://example.org/ns/default/sa/tts-edge", // the internal entity that requested the Txn-Token
  "rctx": {
    "req_ip": "69.151.72.123", // env context of the external call
    "authn": "urn:ietf:rfc:6749", // env context of the external call
  },
  "tctx": {
    "action": "BUY", // parameter of the external call
    "ticker": "MSFT", // parameter of the external call
    "quantity": "100", // parameter of the external call
    "customer_type": { // computed value not present in the external call
      "geo": "US",
      "level": "VIP"
    }
  }
}
```

## Resources
[Human and Workload Identities: Bridging the Gap with Keycloak](https://www.youtube.com/watch?v=TWiBDnq6vmU) | Dmitry Telegin at Keycloak DevDay 2026 Darmstadt
