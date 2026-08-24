package eu.wohlben.qits.configuration.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.configuration.testdb.EmbeddedPg;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * The surface of the <em>packaged artifact</em> — the fast-jar under {@code ./mvnw verify
 * -DskipITs=false}, the GraalVM binary under {@code -Dnative} — because that is where a whole class
 * of failure is visible and nowhere else.
 *
 * <p>Every other test here is a {@code @QuarkusTest}: it augments and runs in the build JVM, with
 * the full classpath present, reflection unrestricted, and its datasource keys handed to it by a
 * config source. A native image has none of those. What this asserts is exactly what that
 * difference can lose:
 *
 * <ul>
 *   <li>the build-time route prefixes — {@code /configuration/api} and {@code /configuration/q} —
 *       which qits-gateway routes verbatim and no unprefixed form falls back to;
 *   <li>the shipped datasource <b>expression</b>: the launched process is handed {@code
 *       QITS_RESOURCE_DB_*}, the generic contract a deployment supplies, rather than the datasource
 *       keys, so the jar's own {@code ${…}} indirection is what is under test;
 *   <li>Flyway's migration surviving as a classpath resource, proven by reading the written row back
 *       over JDBC rather than through the API that wrote it;
 *   <li>every response type reaching Jackson through {@code Response.entity(...)}, which the
 *       build-time analysis cannot see — that is what {@code ApiWireReflection} is for, and a
 *       missing entry there is a 500 in the binary while the JVM suite stays green;
 *   <li><b>the client is served, and does not swallow the API.</b> Quinoa is disabled by default in
 *       test mode, so no {@code @QuarkusTest} builds or serves the SPA and every assertion about
 *       {@code /} would pass against a process with no client in it. The packaged artifact is the
 *       only place either half can be proven.
 * </ul>
 *
 * <p><b>This is also the only place the identity contract is real.</b> A {@code @QuarkusTest} runs
 * under the {@code test} profile, where qits-auth-core ships a dev user; the launched artifact runs
 * as a deployment does, so the roles have to arrive the way the edge sends them — in
 * {@code X-Qits-User} and {@code X-Qits-Roles}. A request with neither is asserted to be refused,
 * which is the claim that this service has no anonymous surface.
 *
 * <p>ITs are skipped by default ({@code skipITs} in the root pom) because they need a {@code
 * package} to have happened. Ask for them explicitly.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedSurfaceIT.PackagedUnderTarget.class)
public class PackagedSurfaceIT {

  /** The database this IT hands the launched process, on a name of its own. */
  private static final String DATABASE = "configuration_packaged_it";

  /**
   * The one string that identifies a response as the CLIENT's index.html rather than anything else
   * this process serves. It is also the string that has to agree with {@code
   * quarkus.quinoa.ui-root-path} here and with {@code baseHref} in qits-spa-configuration's
   * angular.json, so the probes below double as the check that all three still do.
   *
   * <p>It is {@code /} because this service has a host of its own — {@code
   * configuration.<env>.<domain>} — and the client is what that host serves.
   */
  private static final String BASE_HREF = "<base href=\"/\">";

  /**
   * Hands the launched artifact a database the way a deployment does — as the generic resource
   * triple, not as the datasource keys. The configuration jar ships {@code
   * jdbc.url=${QITS_RESOURCE_DB_URL}} and its two siblings, so supplying the variables leaves the
   * <b>shipped</b> expression itself under test.
   *
   * <p>The url travels through a system property rather than a static field: a test profile is
   * instantiated in more than one classloader, so a field written by one copy is not the field the
   * other reads, while the process has exactly one property table.
   */
  public static class PackagedUnderTarget implements QuarkusTestProfile {

    private static final String URL_PROPERTY = "qits.test.packaged-surface-it.db-url";

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "QITS_RESOURCE_DB_URL", databaseUrl(),
          "QITS_RESOURCE_DB_USERNAME", EmbeddedPg.USER,
          "QITS_RESOURCE_DB_PASSWORD", EmbeddedPg.PASSWORD);
    }

    private static synchronized String databaseUrl() {
      String recorded = System.getProperty(URL_PROPERTY);
      if (recorded != null) {
        return recorded;
      }
      // localhost resolves for the launched process too — it is a child of this JVM on this host.
      String url = EmbeddedPg.url(DATABASE);
      System.setProperty(URL_PROPERTY, url);
      return url;
    }
  }

  /** What the edge asserts for an authenticated operator. */
  private static RequestSpecification asAdmin() {
    return given().header("X-Qits-User", "packaged-it").header("X-Qits-Roles", "qits:admin");
  }

  @Test
  public void anEntryRoundTripsThroughFlywayAndPanacheOnTheShippedDatasource() {
    asAdmin()
        .contentType(ContentType.JSON)
        .body(new ConfigurationController.SetEntryRequest("localhost:8081"))
        .when()
        .put("/configuration/api/applications/packaged/entries/env.QITS_REGISTRY")
        .then()
        .statusCode(201)
        .body("entry.value", Matchers.equalTo("localhost:8081"));

    asAdmin()
        .when()
        .get("/configuration/api/applications/packaged/resolved")
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body(
            "properties.'qits.platform.deployments.extras.packaged.env.QITS_REGISTRY'",
            Matchers.equalTo("localhost:8081"))
        .body("headRevision", Matchers.greaterThan(0));

    // The round trip above would look identical against any database at all, so read the row back
    // out of the postgres this JVM handed the process through ${QITS_RESOURCE_DB_URL}. That is the
    // whole claim: the shipped expression resolved, and Flyway's migration survived as a classpath
    // resource — exactly the shape a native image drops.
    assertTrue(
        rowExists("packaged", "env.QITS_REGISTRY"),
        "the packaged process must have written into the resource database");
  }

  @Test
  public void theImportRouteTakesAPropertiesFileOnTheArtifact() {
    asAdmin()
        .contentType(ContentType.TEXT)
        .body(
            """
            # exported from the deployer's config volume
            qits.platform.deployments.extras.packaged-import.aliases[0]=packaged.dev.localhost
            """)
        .when()
        .post("/configuration/api/import")
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("imported", Matchers.equalTo(1))
        .body("ignored", Matchers.equalTo(1));
  }

  @Test
  public void thereIsNoAnonymousSurface() {
    given().when().get("/configuration/api/applications").then().statusCode(401);
  }

  @Test
  public void theRoutesAreWhereTheGatewayRoutesThemAndAMistypedOneIsNever200() {
    asAdmin().when().get("/configuration/api/applications").then().statusCode(200);

    // The edge path-routes verbatim by prefix, so there is no unprefixed form to fall back to.
    // /api is NOT an ignored prefix — the segment is — so the unprefixed spelling now falls to the
    // client's catch-all and answers the page. That is the documented shape of the key: an entry
    // protects a segment, and a path outside it is the browser's.
    asAdmin()
        .when()
        .get("/api/applications")
        .then()
        .statusCode(200)
        .body(Matchers.containsString(BASE_HREF));

    // A mistyped machine path answers Quarkus' own stock page, which is text/html and correct — so
    // what is pinned is the status and the absence of anything a client would parse as data.
    String body =
        asAdmin().when().get("/configuration/api/nope").then().statusCode(404).extract().asString();
    assertFalse(body.contains("headRevision"), "a mistyped path must not answer with data: " + body);
  }

  /**
   * The client is mounted at the root of this service's host, and its {@code <base href>} agrees
   * with where it is mounted. The two are
   * configured in different repositories — {@code quarkus.quinoa.ui-root-path} here, {@code baseHref}
   * in qits-spa-configuration's angular.json — and a disagreement serves a page that loads and then
   * fetches its own JavaScript from a path that 404s. Nothing on this side notices, which is why the
   * string is asserted rather than the status alone.
   *
   * <p><b>It answers anonymously, and that is not a hole in "no anonymous surface".</b> That rule is
   * about this service's DATA: every route in {@link ConfigurationController} is {@code
   * @RolesAllowed} and the test above pins a 401 for an unauthenticated read. What is served here is
   * a static bundle with no configuration in it, and the browser that loaded it gets nothing until
   * the edge's session lets its API calls through.
   */
  @Test
  public void theClientIsServedAtTheRootWithABaseHrefThatMatches() {
    given()
        .when()
        .get("/")
        .then()
        .statusCode(200)
        .contentType(ContentType.HTML)
        .body(Matchers.containsString(BASE_HREF));
  }

  /**
   * A deep link is the SPA fallback doing its job: {@code /applications/qits-docs} has no file
   * behind it, and {@code enable-spa-routing} is what makes a reload or a pasted link reach the
   * Angular router instead of a 404. Both of the client's nested routes are probed, because an
   * operator shares exactly these two addresses — and one scoped spelling, because the platform's
   * URL grammar puts the same page under {@code /<slug>/<category>/<repo>/}.
   */
  @Test
  public void aDeepLinkFallsBackToTheClientSoTheAngularRouterOwnsIt() {
    given()
        .when()
        .get("/applications/qits-docs")
        .then()
        .statusCode(200)
        .contentType(ContentType.HTML)
        .body(Matchers.containsString(BASE_HREF));

    given()
        .when()
        .get("/applications/qits-docs/history")
        .then()
        .statusCode(200)
        .contentType(ContentType.HTML)
        .body(Matchers.containsString(BASE_HREF));

    given()
        .when()
        .get("/qits/services/qits-docs/applications/qits-docs")
        .then()
        .statusCode(200)
        .contentType(ContentType.HTML)
        .body(Matchers.containsString(BASE_HREF));
  }

  /**
   * THE HALF THAT COSTS SOMETHING IF IT IS WRONG. The SPA fallback is a late-order catch-all over
   * the WHOLE host now, so any path that matches no route is rerouted to index.html and answers
   * {@code 200 text/html} — unless {@code quarkus.quinoa.ignored-path-prefixes} claims it first.
   * One entry, {@code /configuration}, is what keeps every machine path out of it.
   *
   * <p>The stake here is the deployer. Its per-deployment read is {@code
   * /configuration/api/applications/<app>/resolved}, and a machine path answering a PAGE would hand
   * a JSON parser an HTML document on the one service whose answer decides what a container starts
   * with.
   *
   * <p><b>What is asserted is the status and the absence of the client's page — not the absence of
   * HTML.</b> An ignored path falls to Quarkus' own not-found handler, which answers {@code 404
   * text/html}: a correct refusal wearing a browser's content type. {@link #BASE_HREF} is the
   * discriminator that means what "never HTML" was reaching for.
   *
   * <p>Each surface the entry covers gets a case here. Add a literal route under the segment and
   * add its line below — the same commit.
   */
  @Test
  public void aMistypedMachinePathIs404AndNeverThePage() {
    // /api — the whole REST surface, including the deployer's resolved read.
    asAdmin()
        .when()
        .get("/configuration/api/nope")
        .then()
        .statusCode(404)
        .body(Matchers.not(Matchers.containsString(BASE_HREF)));

    // /q — health, openapi and swagger-ui. The deployer's health gate curls one of these.
    given()
        .when()
        .get("/configuration/q/nope")
        .then()
        .statusCode(404)
        .body(Matchers.not(Matchers.containsString(BASE_HREF)));
  }

  /**
   * THE WART IS GONE, AND THIS IS WHAT REPLACED IT. Quinoa mounted the client at {@code
   * ui-root-path + "*"}, which did not match the bare segment, so {@code /configuration} answered
   * 404 while {@code /configuration/} was the page (upstream quinoa issue #960). The ui root is
   * {@code /} now, so the client has no segment to be missing the slash of.
   *
   * <p>What {@code /configuration} means instead is the MACHINE segment, and both spellings of it
   * are a 404 rather than the page — which is the whole point of the ignored prefix.
   */
  @Test
  public void theBareSegmentIsTheMachineSurfaceAndNeverThePage() {
    given()
        .when()
        .get("/configuration")
        .then()
        .statusCode(404)
        .body(Matchers.not(Matchers.containsString(BASE_HREF)));

    given()
        .when()
        .get("/configuration/")
        .then()
        .statusCode(404)
        .body(Matchers.not(Matchers.containsString(BASE_HREF)));
  }

  @Test
  public void theReadinessEndpointIsWhereTheDeploymentLooksForIt() {
    given()
        .when()
        .get("/configuration/q/health/ready")
        .then()
        .statusCode(200)
        .body("status", Matchers.equalTo("UP"));
  }

  @Test
  public void theApiDocumentAndItsUiAreServedUnderTheSegment() {
    // Both live under quarkus.http.non-application-root-path, which sits OUTSIDE quarkus.rest.path
    // and carries /configuration on its own; at / they would be unreachable through qits-gateway.
    given().when().get("/configuration/q/openapi").then().statusCode(200);
    given().when().get("/configuration/q/swagger-ui/").then().statusCode(200);
  }

  private static boolean rowExists(String application, String key) {
    String url = EmbeddedPg.url(DATABASE);
    try (Connection connection =
            DriverManager.getConnection(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD);
        PreparedStatement query =
            connection.prepareStatement(
                "select 1 from configuration_entry where application = ? and key = ?")) {
      query.setString(1, application);
      query.setString(2, key);
      try (ResultSet found = query.executeQuery()) {
        return found.next();
      }
    } catch (Exception e) {
      throw new IllegalStateException("could not read the resource database back", e);
    }
  }
}
