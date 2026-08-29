package eu.wohlben.qits.configuration.stories.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The configuration a story <b>walks up to</b> rather than writes — one application, already
 * carrying an entry of each family the deployer understands.
 *
 * <h2>Setup is invisible to the tap, by construction</h2>
 *
 * <p>A story's diagram must show the walk somebody takes, not the fixture somebody built. The one
 * tap that could see this class is the framework's RestAssured filter, which is JVM-global once
 * installed — so everything here drives the API with a plain {@link HttpClient} instead, a client no
 * filter is attached to, and not one fixture request becomes an arrow into qits-configuration.
 *
 * <p>There is no second tap to bound, and that is a fact about this service rather than an
 * omission: qits-configuration reaches out to nothing while it serves a request. Its far side is a
 * single postgres, which no tap can see and which the deployment story <i>declares</i> instead.
 *
 * <h2>Provisioned once, for whichever story class runs first</h2>
 *
 * <p>Every story class that needs it calls {@link #provision()}; the first one does the work and the
 * rest find it done. That is what makes each class runnable on its own ({@code
 * -Dit.test=AccessRefusalIT}) while a full run provisions exactly once.
 *
 * <h2>The names are literals, and the identity is real</h2>
 *
 * <p>The application name carries no run stamp — see {@link StoryTarget} for why a stamped one would
 * move every {@code networkHash}. The fixture's writes go through the same {@code X-Qits-User} /
 * {@code X-Qits-Roles} pair the edge asserts, because a launched artifact has no dev user to fall
 * back on: a fixture that sent no headers would be refused before it wrote anything.
 */
public final class StoryPlatform {

  /** The application the deployment and refusal stories read — configured before they run. */
  public static final String APPLICATION = "story-deployed-app";

  /** An extra environment variable the deployer injects into the container. */
  public static final String ENV_KEY = "env.QITS_FEATURE_FLAGS";

  public static final String ENV_VALUE = "trace-headers";

  /** A named volume, in the deployer's own mount spelling. */
  public static final String MOUNT_KEY = "mounts[0]";

  public static final String MOUNT_VALUE = "qits-story-data:/var/lib/story";

  /** A published port. */
  public static final String PUBLISH_KEY = "publishes[0]";

  public static final String PUBLISH_VALUE = "8080:8080";

  /** A network alias the container answers to on qits-net. */
  public static final String ALIAS_KEY = "aliases[0]";

  public static final String ALIAS_VALUE = "story.dev.localhost";

  /** Who the fixture writes as. It reaches the history, so it says what it is. */
  public static final String FIXTURE_USER = "the-userflow-fixture";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * The fixture's own client — <b>not</b> RestAssured, which is where the tap lives. One per JVM,
   * because a client per request would leave a connection pool behind for each.
   */
  private static final HttpClient CLIENT =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(10))
          .build();

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private static final Object LOCK = new Object();

  private static boolean provisioned;

  private StoryPlatform() {}

  /** The four entries the fixture application holds, by key. */
  public static Map<String, String> entries() {
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put(ENV_KEY, ENV_VALUE);
    entries.put(MOUNT_KEY, MOUNT_VALUE);
    entries.put(PUBLISH_KEY, PUBLISH_VALUE);
    entries.put(ALIAS_KEY, ALIAS_VALUE);
    return entries;
  }

  /**
   * Configure the fixture application, once per JVM. Safe to call from every story class's {@code
   * @BeforeEach} — which is where it has to be called from, because it builds a url out of {@code
   * RestAssured.port} and the Quarkus integration-test extension sets that in <b>beforeEach</b> and
   * clears it back to {@code -1} in afterEach.
   */
  public static void provision() {
    synchronized (LOCK) {
      if (provisioned) {
        return;
      }
      entries().forEach(StoryPlatform::put);
      provisioned = true;
    }
  }

  /** One entry's current value, read through the invisible client, or null when there is none. */
  public static String valueOf(String application, String key) {
    JsonNode resolved = get(StoryTarget.resolvedPath(application));
    JsonNode value =
        resolved.path("properties").path("qits.platform.deployments.extras." + application + "." + key);
    return value.isMissingNode() || value.isNull() ? null : value.asText();
  }

  /** The revision one application's configuration currently stands at. */
  public static long headRevisionOf(String application) {
    return get(StoryTarget.resolvedPath(application)).path("headRevision").asLong();
  }

  // --- the tap-invisible client --------------------------------------------------------------

  private static void put(String key, String value) {
    send(
        request(StoryTarget.entryPath(APPLICATION, key))
            .header("Content-Type", "application/json")
            .PUT(
                HttpRequest.BodyPublishers.ofString(
                    "{\"value\":\"" + value + "\"}", StandardCharsets.UTF_8))
            .build(),
        200,
        201);
  }

  private static JsonNode get(String path) {
    HttpResponse<String> response = send(request(path).GET().build(), 200);
    try {
      return MAPPER.readTree(response.body());
    } catch (IOException e) {
      throw new UncheckedIOException("fixture GET " + path + " answered unparseable JSON", e);
    }
  }

  /**
   * A request builder for one path, with the identity the edge asserts on it.
   *
   * <p>The brackets are escaped by hand, and that is not a nicety: an indexed key is {@code
   * mounts[0]} and {@link URI#create} refuses {@code [} in a path outright — it is reserved for an
   * IPv6 literal in the authority. RestAssured encodes them for the stories; this client is the raw
   * JDK one and has to. The server decodes the path parameter, so what is stored is the key as
   * written.
   */
  private static HttpRequest.Builder request(String path) {
    String encoded = path.replace("[", "%5B").replace("]", "%5D");
    return HttpRequest.newBuilder(URI.create("http://localhost:" + RestAssured.port + encoded))
        .timeout(REQUEST_TIMEOUT)
        .header(StoryIdentities.USER_HEADER, FIXTURE_USER)
        .header(StoryIdentities.ROLES_HEADER, StoryIdentities.HUMAN_ROLE);
  }

  private static HttpResponse<String> send(HttpRequest request, int... accepted) {
    HttpResponse<String> response;
    try {
      response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("fixture " + request.method() + " " + request.uri() + " failed", e);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("fixture " + request.method() + " " + request.uri() + " was interrupted");
    }
    for (int status : accepted) {
      if (response.statusCode() == status) {
        return response;
      }
    }
    throw new IllegalStateException(
        "fixture "
            + request.method()
            + " "
            + request.uri()
            + " answered "
            + response.statusCode()
            + ": "
            + response.body());
  }
}
