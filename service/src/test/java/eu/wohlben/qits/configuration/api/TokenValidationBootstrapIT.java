package eu.wohlben.qits.configuration.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.configuration.testdb.EmbeddedPg;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;

/**
 * The whole service as it is <b>packaged</b> — like {@link PackagedSurfaceIT} beside it, but with
 * the OIDC tenant <b>on</b>, which no {@code @QuarkusTest} here can prove. The shipped tenant is
 * gated: {@code quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}}, and every suite in
 * this repository leaves that gate shut — deliberately, because a clone-alone {@code ./mvnw verify}
 * must need no issuer at all. So the block this service actually deploys with (auth-server-url plus
 * {@code jwks-path=jwks} against a real listener, {@code quarkus.oidc.token.audience} enforcement,
 * the {@code groups} claim becoming roles) is exercised nowhere else. The far side is {@link
 * MockIdp}, whose recordings make the interaction assertable on <b>both ends</b>: it serves a real
 * JWKS for a generated keypair, mints RS256 bearers signed by it — and, on demand, by a key it never
 * published — and records what it answered.
 *
 * <p><b>Why this service in particular.</b> qits-configuration is the platform's config store, and
 * the machine that reads it is qits-platform-deployments: one {@code GET
 * /configuration/api/applications/<app>/resolved} per deployment decides what a container starts
 * with. That read is a bearer's read, so "does a platform token open this service" is not a
 * property of the auth library — it is the precondition of every deployment on the platform.
 *
 * <p>It is also this repo's first <b>userflow</b>: the proof doubles as documentation, emitted under
 * {@code target/userstories/} with the interactions drawn as a sequence diagram. Both stories are
 * browserless (an {@code Interactions} parameter and no {@code Flow}), so the framework's transitive
 * Playwright launches nothing and no Chromium is needed to build this module.
 *
 * <p><b>ITs stay skipped by default and this one does NOT flip that</b>, unlike qits-githost's
 * namesake — even though {@link PackagedSurfaceIT} is the module's only other IT and is dockerless.
 * The reason is the CLIENT: {@code .config/qits/ci-event-userflows.yml} runs with {@code
 * -Dquarkus.quinoa=false} (no submodule, no npm ritual in a step that has no business doing the
 * image build's work), and half of PackagedSurfaceIT's assertions are about the SPA being served —
 * its base href, its deep links, the fallback that must not swallow a machine path. Opting the
 * module back in globally would therefore make every quinoa-less {@code verify} red on a test that
 * is right. Naming the class keeps the opt-in to this pipeline and to this story: {@code -DskipITs=false
 * "-Dit.test=TokenValidationBootstrapIT"}, which is also the incantation to run it by hand.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
public class TokenValidationBootstrapIT {

  static final String CATEGORY = "authentication";
  static final String ACCEPTED_SLUG =
      "on-start-the-configuration-store-fetches-the-platform-s-signing-keys";
  static final String DENIED_SLUG = "a-stranger-s-token-never-opens-the-configuration-store";

  /**
   * The route both stories present a bearer to: the listing of every configured application. It
   * reads rows and nothing else — no other service is dialled, no state is written — and it carries
   * the same {@code @RolesAllowed({"qits:admin", "qits:system"})} pair as the deployer's own
   * resolved read, so what is proved here is what protects that read.
   */
  static final String GUARDED_ROUTE = "/configuration/api/applications";

  /**
   * {@link PackagedSurfaceIT.PackagedUnderTarget} — the resource triple on this JVM's embedded
   * postgres, parked in a system property because a test profile is instantiated in more than one
   * classloader — <b>plus what this story is about</b>: the gate that turns the shipped OIDC tenant
   * on, and where the idp is.
   *
   * <p>Extending rather than copying is deliberate. What a launched qits-configuration needs in
   * order to boot at all is one answer, it is written out at length over there, and a second copy of
   * the parking trick would be a second place for it to drift. The two ITs therefore share the
   * {@code configuration_packaged_it} database as well, which costs nothing: this story asserts the
   * SHAPE of the listing and never a row count, so whatever the sibling wrote is none of its
   * business.
   *
   * <p><b>The eventstream triple is added here rather than inherited, and that is a gap in the
   * parent rather than a decision of this test.</b> This deployable joined qits-eventstream after
   * PackagedSurfaceIT was written: the jar ships {@code
   * quarkus.datasource.eventstream.jdbc.url=${QITS_RESOURCE_EVENTSTREAM_URL}} with no default at
   * all — the refuse-to-boot stance — so a launched process that is handed only the {@code
   * QITS_RESOURCE_DB_*} triple dies at Flyway naming the missing variable. Supplying it is what
   * makes this IT start; supplying it in the PARENT is the follow-up, and the day that happens
   * these three lines come out of here.
   *
   * <p>The mock idp starts <b>before</b> the application, via {@link MockIdp#ensureStarted()}, which
   * parks its coordinates (and its keypair) in system properties for the same classloader reason —
   * that is also how the story method's {@link MockIdp#attach()} reaches the very server the
   * launched process fetched its keys from.
   *
   * <p><b>Every key below is a RUNTIME key.</b> A packaged process takes its configuration as
   * {@code -D} arguments on a jar that was already built, so a build-time key here would be silently
   * ignored and the test would prove the opposite of what it says.
   */
  public static class PackagedWithMockIdp extends PackagedSurfaceIT.PackagedUnderTarget {

    /**
     * The audience this service enforces, and it is the SHIPPED value: {@code
     * qits.auth.machine.audience=qits-configuration} is spelled as a literal in {@code
     * application.properties}, not as an expression over an environment variable, so there is
     * nothing to feed and overriding it would only test a string this test invented. (qits-githost's
     * IT hands its process {@code QITS_AUTH_MACHINE_AUDIENCE} precisely because the expression
     * there reads that variable; the same rollout hit both spellings.) A deployment still overrides
     * it per environment — the default stays the bare name on purpose, since an
     * environment-qualified one would bake one tier into an image every tier shares — and {@code
     * quarkus.oidc.token.audience=${qits.auth.machine.audience}} is what the deny story proves is
     * really read.
     */
    static final String AUDIENCE = "qits-configuration";

    @Override
    public Map<String, String> getConfigOverrides() {
      MockIdp idp = MockIdp.ensureStarted();
      Map<String, String> overrides = new LinkedHashMap<>(super.getConfigOverrides());

      // The outbox's store, as the deployer spells it — `resources: postgresql:eventstream:…` in
      // .config/qits/deployments.yml, and the variable names follow the resource NAME. Dark or not,
      // Quarkus opens this datasource and migrates it at boot.
      overrides.put(
          "QITS_RESOURCE_EVENTSTREAM_URL", EmbeddedPg.url("configuration_eventstream_packaged_it"));
      overrides.put("QITS_RESOURCE_EVENTSTREAM_USERNAME", EmbeddedPg.USER);
      overrides.put("QITS_RESOURCE_EVENTSTREAM_PASSWORD", EmbeddedPg.PASSWORD);

      // THE GATE, and turning it on is the point: the shipped tenant is
      // quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}, so this one key is the
      // difference between a service that validates machine bearers and one that does not. It is
      // the posture a deployed platform takes, and this story is where it is documented. Flipping
      // the derived key directly would prove the tenant and skip the seam.
      overrides.put("qits.auth.machine.required", "true");
      // The one seam this test MOVES: where the idp is. A runtime key, so the packaged artifact is
      // otherwise exactly what ships — discovery stays off and `jwks-path=jwks` is joined onto it.
      overrides.put("quarkus.oidc.auth-server-url", idp.baseUrl());
      // Dark outside a deployment, exactly as %dev and %test have it: a launched process runs in
      // NORMAL mode, where the jar's own default has the bus ENABLED, so without this line the
      // subscriber redials a qits-events nobody is serving and the durable listener sweeps behind
      // the story. Off stops publishing, sweeping and dialling — never the datasource, which is why
      // the triple above is not optional. There is no OTel key to darken beside it: this deployable
      // ships no quarkus-opentelemetry at all (AGENTS.md, "Deliberately not here yet").
      overrides.put("qits.eventstream.enabled", "false");
      return overrides;
    }
  }

  @UserStory(
      value = "On start, the configuration store fetches the platform's signing keys",
      category = "authentication")
  @UserStoryDescription(
      """
      A freshly deployed qits-configuration must validate service bearers before any caller
      arrives: at startup it fetches the signing keys (JWKS) from qits-platform-idp — discovery
      stays off, the path is configured — so the very first machine request is judged on the
      platform's own keys. qits-platform-deployments reads this service with exactly that
      credential, once per deployment, to learn what a container starts with.
      """)
  void serviceBootFetchesJwksAndAcceptsPlatformTokens(Interactions story) {
    MockIdp idp = MockIdp.attach();

    story.note(
        "qits-configuration starts with the OIDC tenant on, beside a reachable qits-platform-idp");
    given().get("/configuration/q/health/ready").then().statusCode(200);

    // End (a), the idp side: the JWKS was served during startup — before this story presented any
    // token at all. That is the claim a status code could never make, and it is only assertable
    // because the mock records what it answered.
    assertTrue(
        idp.recordedRequests().stream().anyMatch(r -> "/idp/jwks".equals(r.path())),
        "the packaged service never fetched /idp/jwks at startup");
    story
        .happened("qits-configuration", "qits-platform-idp", "GET /idp/jwks (at startup)")
        .as("jwks-fetched");

    // End (b), the configuration side: those keys are what token validation now runs on. A platform
    // service's bearer (aud = this service, roles in `groups`) opens the guarded store — the
    // applications listing, which names qits:system beside qits:admin because the deployer and an
    // operator both read this surface.
    String platformToken =
        idp.token()
            .subject("qits-platform-deployments")
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits:system")
            .mint();
    given()
        .header("Authorization", "Bearer " + platformToken)
        .get(GUARDED_ROUTE)
        .then()
        .statusCode(200)
        .body("applications", notNullValue());
    story
        .happened(
            "a platform service",
            "qits-configuration",
            "GET " + GUARDED_ROUTE + " (Bearer, groups=[qits:system])")
        .as("configuration-served");
  }

  @UserStory(
      value = "A stranger's token never opens the configuration store",
      category = "authentication")
  @UserStoryDescription(
      """
      The flip side of trusting the platform's keys: a token signed by a key the published JWKS
      never carried, or minted for another service's audience, is refused at the door — however
      well-formed it looks. Both are 401 and not 403: the credential never became an identity, so
      there is no caller to have been forbidden. This service holds what every deployment on the
      platform is configured from, so it has no anonymous surface and no second door.
      """)
  void aStrangersTokenIsRefused(Interactions story) {
    MockIdp idp = MockIdp.attach();

    String strangersToken =
        idp.token()
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits:system")
            .signedByUnknownKey()
            .mint();
    given()
        .header("Authorization", "Bearer " + strangersToken)
        .get(GUARDED_ROUTE)
        .then()
        .statusCode(401);
    story
        .happened(
            "an impostor",
            "qits-configuration",
            "GET " + GUARDED_ROUTE + " (token signed by an unknown key) -> 401")
        .as("unknown-key-refused");

    // The audience half, which is what proves quarkus.oidc.token.audience=${qits.auth.machine.audience}
    // is read rather than assumed: this token is signed by the very key the JWKS published and is
    // still refused, because it was cut for somebody else.
    String wrongAudienceToken =
        idp.token().audience("some-other-service").groups("qits:system").mint();
    given()
        .header("Authorization", "Bearer " + wrongAudienceToken)
        .get(GUARDED_ROUTE)
        .then()
        .statusCode(401);
    story
        .happened(
            "an impostor",
            "qits-configuration",
            "GET " + GUARDED_ROUTE + " (another service's audience) -> 401")
        .as("wrong-audience-refused");
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    // This is the check that the DOCUMENTATION was produced, not a second check that the service
    // behaved — a story whose report never landed publishes an empty bundle and says nothing.
    ReportAssertions.assertComplete(CATEGORY, ACCEPTED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertInteraction(
        CATEGORY,
        ACCEPTED_SLUG,
        "qits-configuration",
        "qits-platform-idp",
        "GET /idp/jwks (at startup)");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "jwks-fetched");

    ReportAssertions.assertComplete(CATEGORY, DENIED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "unknown-key-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "wrong-audience-refused");
  }
}
