package pro.carretti.keycloak.tts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.Charset;

import org.jboss.logging.Logger;

import org.keycloak.OAuth2Constants;
import org.keycloak.OAuthErrorException;
import org.keycloak.Token;
import org.keycloak.TokenCategory;
import org.keycloak.authentication.authenticators.client.FederatedJWTClientAuthenticator;
import org.keycloak.broker.oidc.OIDCIdentityProvider;
import org.keycloak.broker.oidc.OIDCIdentityProviderConfig;
import org.keycloak.common.util.Time;
import org.keycloak.crypto.SignatureProvider;
import org.keycloak.crypto.SignatureSignerContext;
import org.keycloak.jose.jws.JWSBuilder;
import org.keycloak.models.ClientModel;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.protocol.oidc.TokenExchangeContext;
import org.keycloak.protocol.oidc.TokenExchangeProvider;
import org.keycloak.representations.AccessTokenResponse;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.JsonWebToken;
import org.keycloak.services.Urls;
import org.keycloak.util.JsonSerialization;
import org.keycloak.utils.OAuth2Error;
import org.keycloak.utils.StringUtil;

public class TTSTokenExchangeProvider implements TokenExchangeProvider {

    static final String TXN_TOKEN_REQUESTED_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:txn_token";

    private static final Logger LOG = Logger.getLogger(TTSTokenExchangeProvider.class);
    private static final String TTS_IDP_ALIAS = "tts";
    static final String TXN_TOKEN_TYPE = "txntoken+jwt";
    private static final String N_A = "N_A";
    private static final String REQUEST_CONTEXT = "request_context";
    private static final String REQUEST_DETAILS = "request_details";

    // Transaction Token claims
    private static final String RCTX = "rctx";
    static final String REQ_WL = "req_wl";
    private static final String TCTX = "tctx";
    static final String TXN = "txn";

    private static final String TTS_IDP_ALLOWED_AUDIENCE = "tts.audience";
    private static final String TTS_IDP_TXN_TOKEN_LIFESPAN = "tts.token.lifespan";
    static final long TXN_TOKEN_LIFESPAN = 5;

    private final KeycloakSession session;
    private final RealmModel realm;
    private final ClientModel client;

    private OIDCIdentityProvider idp;
    private long lifespan = TXN_TOKEN_LIFESPAN;

    private String audience;
    private String scope;
    private String requestContext;
    private String requestDetails;

    public TTSTokenExchangeProvider(KeycloakSession session) {
        this.session = session;
        this.realm = session.getContext().getRealm();
        this.client = session.getContext().getClient();
        // TODO: event
    }

    @Override
    public boolean supports(TokenExchangeContext context) {
        String requestedTokenType = context.getParams().getRequestedTokenType();
        return TXN_TOKEN_REQUESTED_TOKEN_TYPE.equals(requestedTokenType);
    }

    @Override
    public Response exchange(TokenExchangeContext context) {
        TokenExchangeContext.Params params = context.getParams();

        LOG.debug("TTS::exchange");

        // REQUIRED

        var audiences = params.getAudience();
        if (audiences == null || audiences.isEmpty()) {
            LOG.warn("Missing audience parameter");
            throw new OAuth2Error().invalidRequest("Missing audience parameter");
        } else if (audiences.size() > 1) {
            LOG.warn("Multiple audiences provided");
            throw new OAuth2Error().invalidRequest("Multiple audiences provided");
        } else {
            this.audience = audiences.getFirst();
        }

        this.scope = params.getScope();
        if (StringUtil.isBlank(scope)) {
            LOG.warn("Missing scope parameter");
            throw new OAuth2Error().invalidRequest("Missing scope parameter");
        }

        String subjectToken = params.getSubjectToken();
        if (StringUtil.isBlank(subjectToken)) {
            LOG.warn("Missing subject_token parameter");
            throw new OAuth2Error().invalidRequest("Missing subject_token parameter");
        }

        String subjectTokenType = params.getSubjectTokenType();
        if (StringUtil.isBlank(subjectTokenType)) {
            LOG.warn("Missing subject_token_type parameter");
            throw new OAuth2Error().invalidRequest("Missing subject_token_type parameter");
        }

        // OPTIONAL
        this.requestContext = context.getFormParams().getFirst(REQUEST_CONTEXT);
        this.requestDetails = context.getFormParams().getFirst(REQUEST_DETAILS);

        if (!OAuth2Constants.ACCESS_TOKEN_TYPE.equals(subjectTokenType)) {
            LOG.warnv("Subject token type not recognized: {0}", subjectTokenType);
            throw new OAuth2Error().invalidRequest("Subject token type not recognized: " + subjectTokenType);
        }

        this.idp = getIdP();
        if (idp == null) {
            LOG.warn("TTS::exchange failed: No TTS IdP configured - please consult the README");
            throw new OAuth2Error().error(OAuthErrorException.SERVER_ERROR).errorDescription("No TTS IdP configured").build();
        }

        LOG.debugv("TTS::exchange IdP = {0}", idp);

        String sLifespan = idp.getConfig().getConfig().get(TTS_IDP_TXN_TOKEN_LIFESPAN);
        if (StringUtil.isNotBlank(sLifespan))
            this.lifespan = Long.parseLong(sLifespan);

        String allowedAudience = idp.getConfig().getConfig().get(TTS_IDP_ALLOWED_AUDIENCE);
        if (StringUtil.isBlank(allowedAudience)) {
            LOG.warn("TTS::exchange failed: allowed audience not configured - please consult the README");
            throw new OAuth2Error().error(OAuthErrorException.SERVER_ERROR).errorDescription("Allowed audience not configured").build();
        }

        if (!allowedAudience.equals(audience)) {
            LOG.warnv("Audience not allowed: ", audience);
            throw new OAuth2Error().invalidRequest("Audience not allowed: " + audience);
        }

        JsonWebToken jwt = idp.validateToken(subjectToken);
        try {
            LOG.debugv("Validated subject token:\n{0}", JsonSerialization.writeValueAsPrettyString(jwt));
        } catch (IOException ex) {
            LOG.warn("Error processing JSON", ex);
        }

        AccessToken txnToken = createTxnToken(jwt);
        String txnTokenString = encode(txnToken);

        LOG.debug("TTS::exchange successful");
        return createResponse(txnTokenString);
    }

    private AccessToken createTxnToken(JsonWebToken jwt) {
        AccessToken token = new AccessToken();

        // REQUIRED
        token.issuedNow();
        token.exp(Time.currentTime() + lifespan);
        token.audience(this.audience);
        token.setOtherClaims(TXN, KeycloakModelUtils.generateId());
        token.setSubject(client.getClientId());

        String wlid = client.getAttribute(FederatedJWTClientAuthenticator.JWT_CREDENTIAL_SUBJECT_KEY);
        token.setOtherClaims(REQ_WL, StringUtil.isNotBlank(wlid) ? wlid : client.getClientId());
        token.setOtherClaims(OAuth2Constants.SCOPE, getScope());

        // OPTIONAL
        String issuer = Urls.realmIssuer(session.getContext().getUri().getBaseUri(), realm.getName());
        token.issuer(issuer);

        if (StringUtil.isNotBlank(requestContext)) {
            String decoded = URLDecoder.decode(requestContext, Charset.defaultCharset());
            try {
                RequestContext rctx = JsonSerialization.readValue(decoded, RequestContext.class);
                token.setOtherClaims(RCTX, rctx);
            } catch (IOException ex) {
                LOG.errorv(ex, "Error parsing {0}: {1}", REQUEST_CONTEXT, decoded);
            }
        }

        // TODO: tctx

        return token;
    }

    private String encode(Token token) {
        String signatureAlgorithm = session.tokens().signatureAlgorithm(TokenCategory.ACCESS);
        SignatureProvider signatureProvider = session.getProvider(SignatureProvider.class, signatureAlgorithm);
        SignatureSignerContext signer = signatureProvider.signer();

        String encodedToken = new JWSBuilder().type(TXN_TOKEN_TYPE).jsonContent(token).sign(signer);
        return encodedToken;
    }

    private Response createResponse(String accessToken) {
        AccessTokenResponse tokenResponse = new TxnTokenResponse();
        tokenResponse.setTokenType(N_A);
        tokenResponse.setToken(accessToken);
        tokenResponse.setIdToken(null);
        tokenResponse.setRefreshToken(null);
        tokenResponse.getOtherClaims().clear();
        tokenResponse.getOtherClaims().put(OAuth2Constants.ISSUED_TOKEN_TYPE, TXN_TOKEN_REQUESTED_TOKEN_TYPE);
//        event.success();
        return Response.ok(tokenResponse).type(MediaType.APPLICATION_JSON_TYPE).build();
    }

    private String getScope() {
        // TODO: validate & map
        return scope;
    }

    @Override
    public int getVersion() {
        return 0;
    }

    @Override
    public void close() {
    }

    private OIDCIdentityProvider getIdP() {
        IdentityProviderModel model = session.identityProviders().getByAlias(TTS_IDP_ALIAS);
        if (model != null) {
            OIDCIdentityProviderConfig config = new OIDCIdentityProviderConfig(model);
            return new OIDCIdentityProvider(session, config);
        }
        return null;
    }

    @JsonIgnoreProperties({ "expires_in", "refresh_expires_in", "not-before-policy" })
    class TxnTokenResponse extends AccessTokenResponse {

    }

    record RequestContext(@JsonProperty("req_ip") String reqIP, String authn) {  }

}
