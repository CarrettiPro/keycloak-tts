package pro.carretti.keycloak.tts;

import dasniko.testcontainers.keycloak.KeycloakContainer;

import static io.restassured.RestAssured.given;
import io.restassured.response.ValidatableResponse;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.OAuth2Constants;
import org.keycloak.TokenVerifier;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.IdentityProviderResource;
import org.keycloak.common.VerificationException;
import org.keycloak.common.util.Time;
import org.keycloak.jose.jws.JWSHeader;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.idm.IdentityProviderRepresentation;
import org.keycloak.utils.MediaType;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public class TTSTokenExchangeProviderIT extends TestBase {

    private static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.6.0";
    private static final String AS_REALM_JSON = "as-external.json";
    private static final String TTS_REALM_JSON = "tts-internal.json";

    private static final String AS_REALM = "external";
    private static final String AS_CLIENT = "frontend";
    private static final String AS_USERNAME = "user";
    private static final String AS_PASSWORD = "user";

    private static final String TTS_REALM = "internal";
    private static final String TTS_CLIENT = "tts-client";
    private static final String TTS_CLIENT_SECRET = "my-special-client-secret";
    private static final String TTS_AUDIENCE = "example.org";
    private static final String TTS_IDP = "tts";
    private static final String TTS_SCOPE = "test";

    @Container
    static KeycloakContainer keycloak = new KeycloakContainer(KEYCLOAK_IMAGE)
            .withRealmImportFiles(AS_REALM_JSON, TTS_REALM_JSON)
            .withDefaultProviderClasses();

    @Test
    void testHappyPath() throws VerificationException {
        String issuer = getOpenIDConfiguration(keycloak, TTS_REALM).extract().path("issuer");
        String at = requestToken(keycloak, AS_REALM, AS_CLIENT, AS_USERNAME, AS_PASSWORD).extract().path("access_token");
        String tt = requestTransactionToken(keycloak, TTS_REALM, TTS_CLIENT, TTS_CLIENT_SECRET, TTS_AUDIENCE, at, 200).extract().path("access_token");

        TokenVerifier<AccessToken> verifier = TokenVerifier.create(tt, AccessToken.class);
        JWSHeader header = verifier.getHeader();
        AccessToken token = verifier.getToken();

        // Header
        assertThat(header.getType(), is(TTSTokenExchangeProvider.TXN_TOKEN_TYPE));

        // Payload
        assertThat((double)token.getExp(), closeTo(Time.currentTime() + TTSTokenExchangeProvider.TXN_TOKEN_LIFESPAN, 50));
        assertThat((double)token.getIat(), closeTo(Time.currentTime(), 50));
        assertThat(token.getIssuer(), is(issuer));
        assertThat(token.getAudience(), is(new String[] { TTS_AUDIENCE }));
        assertThat(token.getSubject(), is(TTS_CLIENT));
        assertThat(token.getScope(), is(TTS_SCOPE));
        assertThat(token.getOtherClaims().get(TTSTokenExchangeProvider.REQ_WL), is(TTS_CLIENT));
        assertThat(token.getOtherClaims().get(TTSTokenExchangeProvider.TXN), notNullValue());
    }

    public TTSTokenExchangeProviderIT() {
    }

    @BeforeAll
    public static void setUpClass() {
        // TestContainers pick random port for Keycloak, which becomes part of the OAuth issuer property.
        // The value should match the respective configuration property of the IdentityProvider in Keycloak,
        // otherwise we will get a "Wrong issuer from token" exception.
        String issuer = getOpenIDConfiguration(keycloak, AS_REALM).extract().path("issuer");
        Keycloak admin = keycloak.getKeycloakAdminClient();
        IdentityProviderResource idp = admin.realm(TTS_REALM).identityProviders().get(TTS_IDP);
        IdentityProviderRepresentation rep = idp.toRepresentation();
        rep.getConfig().put("issuer", issuer);
        idp.update(rep);
    }

    @AfterAll
    public static void tearDownClass() {
    }

    @BeforeEach
    public void setUp() {
    }

    @AfterEach
    public void tearDown() {
    }

    private ValidatableResponse requestTransactionToken(KeycloakContainer keycloak, String realm, String clientId, String clientSecret, String audience, String token, int expectedStatusCode) {
            String tokenEndpoint = getOpenIDConfiguration(keycloak, realm)
                    .extract().path("token_endpoint");
            return given()
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .formParam(OAuth2Constants.GRANT_TYPE, OAuth2Constants.TOKEN_EXCHANGE_GRANT_TYPE)
                    .formParam(OAuth2Constants.CLIENT_ID, clientId)
                    .formParam(OAuth2Constants.CLIENT_SECRET, clientSecret)
                    .formParam(OAuth2Constants.AUDIENCE, audience)
                    .formParam(OAuth2Constants.SUBJECT_TOKEN_TYPE, OAuth2Constants.ACCESS_TOKEN_TYPE)
                    .formParam(OAuth2Constants.SUBJECT_TOKEN, token)
                    .formParam(OAuth2Constants.REQUESTED_TOKEN_TYPE, TTSTokenExchangeProvider.TXN_TOKEN_REQUESTED_TOKEN_TYPE)
                    .formParam(OAuth2Constants.SCOPE, TTS_SCOPE)
                    .when().post(tokenEndpoint)
                    .then().statusCode(expectedStatusCode);
    }

}
