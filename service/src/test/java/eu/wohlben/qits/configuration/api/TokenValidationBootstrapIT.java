package eu.wohlben.qits.configuration.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.configuration.stories.support.StoryEventBus;
import eu.wohlben.qits.configuration.testdb.EmbeddedPg;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.NetworkTaps;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

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
 * <p>It is also this repo's first <b>userflow</b>, and the one the rest of the catalogue under
 * {@code stories/} runs behind: the proof doubles as documentation, emitted under {@code
 * target/userstories/} with a network diagram beside the steps. The diagram is <b>observed, never
 * narrated</b> — the framework's own RestAssured tap draws what a story sends into this service,
 * {@link MockIdp}'s recordings supply what this service sent to the idp, and the framework drains
 * both at story end. A story method therefore asserts and notes; it draws nothing. Both stories are
 * browserless (an {@code Interactions} parameter and no {@code Flow}), so the framework's transitive
 * Playwright launches nothing and no Chromium is needed to build this module.
 *
 * <p><b>The two stories are ordered</b>, and that is load-bearing rather than tidiness: a cumulative
 * source is attributed by a cursor, so traffic that happened before any story ran — the startup JWKS
 * fetch, which is the whole subject of the first story — lands in whichever story drains
 * <i>first</i>. Pinning the order is what keeps that the story it belongs to.
 *
 * <p>The same argument reaches one level up: this class runs before every class under {@code
 * stories/} because failsafe orders by FQCN within a profile group and {@code api} sorts before
 * {@code stories}. That is why the JWKS fetch is <b>here</b> and not in whichever catalogue story
 * happened to run first. See AGENTS.md § Userflows for the whole ordering.
 *
 * <p><b>ITs stay skipped by default and this one does NOT flip that</b>, unlike qits-githost's
 * namesake — even though {@link PackagedSurfaceIT} is the module's only other IT and is dockerless.
 * The reason is the CLIENT: {@code .config/qits/ci-event-userflows.yml} runs with {@code
 * -Dquarkus.quinoa=false} (no submodule, no npm ritual in a step that has no business doing the
 * image build's work), and half of PackagedSurfaceIT's assertions are about the SPA being served —
 * its base href, its deep links, the fallback that must not swallow a machine path. Opting the
 * module back in globally would therefore make every quinoa-less {@code verify} red on a test that
 * is right. Naming the classes keeps the opt-in to this pipeline and to these stories; the list is
 * spelled in {@code .config/qits/ci-event-userflows.yml} and in AGENTS.md § Userflows, and a story
 * class that is not on it is written and never run.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
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

  /** How the diagram names this service on both sides of an edge. */
  static final String SERVICE = "qits-configuration";

  /**
   * The peer whose bearer this story presents, named rather than described.
   *
   * <p>It is the platform's deployer, and it is the one caller whose read decides something: one
   * {@code GET …/resolved} per deployment is what a container starts with. Naming the node — instead
   * of "a platform service" — is what makes the aggregate diagram of this catalogue show one
   * deployer rather than two things that mean the same.
   */
  static final String DEPLOYER = "qits-platform-deployments";

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
      // THE BUS IS LIT, and pointed at a stub, because one of this service's user stories is about
      // it. %dev and %test darken it (a clone-alone build has no qits-events on the far side), but a
      // launched process runs in NORMAL mode where the jar's own default is ENABLED — which is the
      // posture a deployment takes, and the only one in which bus/SoftwareReleaseListener consumes
      // anything at all. Darkening it here would make stories/release/ImageReleasePinIT document a
      // path that was switched off while it ran. See StoryEventBus for what the far side answers and
      // for why an empty poll is not an arrow. There is no OTel key to darken beside it: this
      // deployable ships no quarkus-opentelemetry at all (AGENTS.md, "Deliberately not here yet").
      overrides.put("qits.eventstream.enabled", "true");
      overrides.put("qits.events.url", StoryEventBus.ensureStarted());
      // The catch-up sweep is this service's ONLY way in from the bus while the stub cannot upgrade
      // a websocket, so the story's release arrives on a tick rather than on a push. Two seconds is
      // short enough that a story does not wait on it and long enough that the log is not hammered;
      // the shipped default is PT30S, which is a safety net's cadence rather than a test's.
      overrides.put("qits.eventstream.catchup-interval", "PT2S");
      return overrides;
    }
  }

  /**
   * Wires both halves of the network diagram, once, before either story runs.
   *
   * <p>The near side (what a story sends here) is the tap the <b>framework ships</b>: {@code
   * NetworkTaps.restAssured}, idempotent per service, which every story class in this repository
   * installs the same way. The local {@code StoryNetworkFilter} this class used to carry beside it
   * is gone — a per-repo copy of a shipped tap is the thing that goes out of step. Its default skip
   * is any path with a {@code /q/} segment, which is right here: {@code
   * quarkus.http.non-application-root-path} is {@code /configuration/q}, so the readiness probe
   * below is out of every diagram and no route this service owns is.
   *
   * <p>The idp is the far side, registered as a <b>cumulative</b> source: the supplier hands over
   * the mock's whole request log every time it is asked and the framework remembers how much of it
   * earlier stories already consumed, so the startup fetch — recorded long before any story existed
   * — is attributed to the first story and to that one only. It is invoked lazily at story end, so
   * registering it here is safe even though nothing has been recorded yet.
   *
   * <p>The label carries the status the mock <i>answered</i> with, which is the half a method and
   * path cannot supply: {@code "GET /idp/jwks -> 200"} is evidence that the keys were served, not
   * merely asked for.
   */
  @BeforeAll
  static void tapBothEndsOfTheNetwork() {
    NetworkTaps.restAssured(SERVICE);
    NetworkCapture.source(
        "mock-idp",
        () ->
            MockIdp.attach().recordedRequests().stream()
                .map(
                    request ->
                        NetworkEdge.http(
                            SERVICE,
                            MockIdp.SERVICE_NAME,
                            request.method() + " " + request.path() + " -> " + request.status()))
                .toList());
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
  @Order(1)
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
        .note("the signing keys were fetched at startup, before this story presented any token")
        .as("jwks-fetched");

    // End (b), the configuration side: those keys are what token validation now runs on. A platform
    // service's bearer (aud = this service, roles in `groups`) opens the guarded store — the
    // applications listing, which names qits:system beside qits:admin because the deployer and an
    // operator both read this surface.
    //
    // The actor is set BEFORE the call: the tap sees a request, never a narrative role, and this is
    // what makes the observed edge read `qits-platform-deployments -> qits-configuration`.
    NetworkCapture.actor(DEPLOYER);
    String platformToken =
        idp.token()
            .subject(DEPLOYER)
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
        .note("the deployer's bearer (aud=qits-configuration, groups=[qits:system]) opens it")
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
  @Order(2)
  void aStrangersTokenIsRefused(Interactions story) {
    MockIdp idp = MockIdp.attach();

    // Everything this story sends is an impostor's, so the actor is set once, up front.
    NetworkCapture.actor("an impostor");

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
    // Both refusals are the same edge — same actor, same route, same status — so the diagram draws
    // one arrow and the notes are what keep the two credentials distinguishable. That is the right
    // division: the graph says who reached what and got what, the steps say why.
    story
        .note("a token signed by a key the published JWKS never carried is refused")
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
        .note("a token minted for another service's audience is refused just the same")
        .as("wrong-audience-refused");
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    // This is the check that the DOCUMENTATION was produced, not a second check that the service
    // behaved — a story whose report never landed publishes an empty bundle and says nothing.
    // assertComplete now also proves the network section: the sidecar's edges are canonical, the
    // networkHash recomputes from them, and every mermaid line is in the markdown.
    ReportAssertions.assertComplete(CATEGORY, ACCEPTED_SLUG, UserflowReport.PASSED);
    // Observed on the far side, drained from the mock's recording, and attributed to this story
    // because it is the first one that ran (see the class javadoc on ordering).
    ReportAssertions.assertEdge(
        CATEGORY, ACCEPTED_SLUG, "http", SERVICE, MockIdp.SERVICE_NAME, "GET /idp/jwks -> 200");
    // Observed on the near side, by the filter, with the actor this story set.
    ReportAssertions.assertEdge(
        CATEGORY, ACCEPTED_SLUG, "http", DEPLOYER, SERVICE, "GET " + GUARDED_ROUTE + " -> 200");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "jwks-fetched");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "configuration-served");

    ReportAssertions.assertComplete(CATEGORY, DENIED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertEdge(
        CATEGORY, DENIED_SLUG, "http", "an impostor", SERVICE, "GET " + GUARDED_ROUTE + " -> 401");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "unknown-key-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "wrong-audience-refused");
  }
}
