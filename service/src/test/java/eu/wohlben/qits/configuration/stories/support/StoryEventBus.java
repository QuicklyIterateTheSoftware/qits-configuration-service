package eu.wohlben.qits.configuration.stories.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.userflows.Labels;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A stand-in qits-events, and the <b>outgoing</b> tap that draws what the launched process read from
 * it.
 *
 * <p>This is the one dependency qits-configuration has that is not its own database. It does not
 * call qits-ci and qits-ci does not call it: {@code bus/SoftwareReleaseListener} is a {@code
 * QitsDurableEventListener}, so a released docker image reaches this service as a row in the
 * platform's event log which this service <i>pages forward into</i> — {@code GET
 * /events/api/events?order=asc&…}, from its own watermark, on qits-eventstream's catch-up sweep.
 * Nothing pushes configuration and nothing pushes a release; both ends pull. A story that wants to
 * document that needs a log to page, which is what this class serves.
 *
 * <h2>Armed by a file, on purpose</h2>
 *
 * <p>The server is started from the {@code QuarkusTestProfile}, because the launched process needs
 * {@code qits.events.url} before it boots — and a test profile is instantiated in more than one
 * classloader, so the story that arms the log is not holding the object that serves it. Every piece
 * of shared state is therefore a <b>file</b>: {@link #arm} writes the page the log will answer with,
 * and the server reads it per request. The port travels in a system property for the same reason,
 * exactly as {@code MockService} does.
 *
 * <h2>What it answers, and why that is enough</h2>
 *
 * <p>{@code CatchupSweeper} asks two questions and this serves both:
 *
 * <ul>
 *   <li><b>the newest matching event</b> ({@code ?limit=1&name=…}), asked once, when a consumer has
 *       no watermark. Answered <b>empty</b>, which is how a consumer that has never run starts at
 *       the beginning of a log rather than skipping to its head — the state a fresh eventstream
 *       database is in, and the only one that lets a story arm a release afterwards and have it
 *       delivered.
 *   <li><b>the next page after a cursor</b> ({@code ?order=asc&limit=200&name=…}). Armed or not,
 *       this is the request the sweep repeats; it carries the armed page exactly when there is no
 *       {@code cursor} parameter yet, i.e. until the consumer has read past the release. After that
 *       the watermark supplies a cursor and every later sweep gets an empty page, which is why one
 *       arming is delivered exactly once.
 * </ul>
 *
 * <h2>The tap, and its one deliberate exclusion</h2>
 *
 * <p>Every answered request is appended to a file as {@code METHOD URI STATUS carried|empty} —
 * before the response is written, so a line is on disk by the time its effect is observable — and
 * {@link #install()} takes the end of that file as a <b>floor</b>, then harvests cumulatively and
 * prefix-stably, which is what the framework's per-source cursor requires.
 *
 * <p><b>An empty poll is not an edge.</b> The catch-up sweep is a timer: it fires every {@code
 * qits.eventstream.catchup-interval} whether or not a story is running, so drawing an arrow for one
 * would put a heartbeat in whichever story happened to be open when it ticked, and the diagram would
 * differ run to run with nothing having changed. What is kept is the poll that <i>carried</i> the
 * release — a dependency a story exercised and can point at. The websocket redial ({@code
 * /events/stream}, which this server cannot upgrade and answers 404) is excluded for the same reason
 * and is stated here rather than drawn.
 */
public final class StoryEventBus {

  /** How a diagram names the service this server impersonates — the platform's event log. */
  public static final String SERVICE_NAME = "qits-events";

  /** The list route under {@code qits.events.url}, which is a bare scheme + host + port. */
  public static final String EVENTS_PATH = "/events/api/events";

  /** The signature this service's durable listener subscribes to. */
  public static final String SOFTWARE_RELEASE = "SoftwareRelease";

  /**
   * The label a carried poll renders as. {@code order}, {@code limit} and {@code name} are all
   * authored constants — {@code CatchupSweeper.PAGE_SIZE} is 200 and the filter is the listener's
   * one signature — so this survives {@link Labels#scrub} unchanged and the {@code networkHash} does
   * not move between runs. The cursor-carrying spelling never reaches a label, because a poll that
   * has a cursor is a poll that carried nothing.
   */
  public static final String CATCHUP_LABEL =
      "GET " + EVENTS_PATH + "?order=asc&limit=200&name=" + SOFTWARE_RELEASE + " -> 200";

  private static final String PORT_PROPERTY = "qits.test.story-event-bus.port";

  private static final String SOURCE_ID = "story-event-bus";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Path ROOT = Path.of("target", "it-events-stub");

  /** The page the log answers a cursorless ascending read with, once a story has armed one. */
  private static final Path ARMED = ROOT.resolve("armed-page.json");

  /** The recording: one line per answered request, the same shape a git host's access log has. */
  private static final Path ACCESS_LOG = ROOT.resolve("access.log");

  private static final String EMPTY_PAGE = "{\"events\":[],\"nextCursor\":null}";

  private static final Object LOCK = new Object();

  private static boolean registered;

  private static int floor;

  private static int harvested;

  private static final List<NetworkEdge> EDGES = new ArrayList<>();

  private StoryEventBus() {}

  // --- the server ------------------------------------------------------------------------------

  /**
   * Start the stub once per JVM and park its port, wiping whatever an earlier run left behind — an
   * armed page surviving into a new run would be delivered at startup, before any story could own
   * it. Called from the test profile, which is the only place that knows the url in time.
   */
  public static synchronized String ensureStarted() {
    String port = System.getProperty(PORT_PROPERTY);
    if (port != null) {
      return baseUrl(Integer.parseInt(port));
    }
    wipe();
    HttpServer server;
    try {
      server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException("could not start the qits-events stub", e);
    }
    server.createContext("/", StoryEventBus::handle);
    server.start();
    System.setProperty(PORT_PROPERTY, String.valueOf(server.getAddress().getPort()));
    return baseUrl(server.getAddress().getPort());
  }

  private static String baseUrl(int port) {
    return "http://localhost:" + port;
  }

  private static void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String query = exchange.getRequestURI().getQuery();
    String body = EMPTY_PAGE;
    boolean carried = false;
    int status = 404;
    if (EVENTS_PATH.equals(path)) {
      status = 200;
      if (query != null && query.contains("order=asc") && !query.contains("cursor=")) {
        String armed = armedPage();
        if (armed != null) {
          body = armed;
          carried = true;
        }
      }
    }
    // Recorded BEFORE the answer leaves, so a story that observes the effect of a page can rely on
    // the line for it already being on disk. There is nothing to await.
    record(exchange.getRequestMethod(), uri(path, query), status, carried);
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static String uri(String path, String query) {
    return query == null || query.isBlank() ? path : path + "?" + query;
  }

  // --- arming ----------------------------------------------------------------------------------

  /**
   * Put a page of frames in the log, to be delivered on the next catch-up sweep.
   *
   * <p>{@code nextCursor} is null because this is the last page there is: a reader must not infer
   * the end of a log from a short page, so the field is the only thing that ends the loop.
   */
  public static void arm(List<Map<String, Object>> frames) {
    Map<String, Object> page = new LinkedHashMap<>();
    page.put("events", frames);
    page.put("nextCursor", null);
    try {
      Files.createDirectories(ROOT);
      Files.writeString(ARMED, MAPPER.writeValueAsString(page), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not arm the qits-events stub", e);
    }
  }

  /**
   * One {@code SoftwareRelease} frame as qits-ci publishes it: the envelope's own fields, and the
   * payload as the <b>string</b> the log stores verbatim — which is what {@code CanonicalJson}
   * hands the listener to decode.
   *
   * <p>{@code occurredAt} is an authored ISO instant rather than {@code now()}: it becomes the
   * consumer's watermark and nothing about the story depends on when it ran.
   */
  public static Map<String, Object> softwareRelease(
      String id,
      String occurredAt,
      String repository,
      String version,
      String packageType,
      String packageName) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("repository", repository);
    payload.put("version", version);
    payload.put("packageType", packageType);
    payload.put("packageName", packageName);
    Map<String, Object> frame = new LinkedHashMap<>();
    frame.put("id", id);
    frame.put("name", SOFTWARE_RELEASE);
    frame.put("occurredAt", occurredAt);
    frame.put("payload", json(payload));
    frame.put("description", repository + " released " + packageName + " " + version);
    frame.put("parentId", null);
    frame.put("environment", "platform");
    return frame;
  }

  private static String json(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (IOException e) {
      throw new UncheckedIOException("could not serialize a stub frame", e);
    }
  }

  // --- the source ------------------------------------------------------------------------------

  /**
   * Register the tap once per JVM, taking the current end of the recording as the floor. Called from
   * every story class's {@code @BeforeEach}, which is idempotent per JVM: whichever class runs first
   * bounds what any story can see, and everything the process polled while it was booting is below
   * the line.
   */
  public static void install() {
    synchronized (LOCK) {
      if (registered) {
        return;
      }
      floor = allLines().size();
      harvested = 0;
      NetworkCapture.source(SOURCE_ID, StoryEventBus::edges);
      registered = true;
    }
  }

  private static List<NetworkEdge> edges() {
    synchronized (LOCK) {
      harvest();
      return List.copyOf(EDGES);
    }
  }

  private static void harvest() {
    List<String> lines = readLines();
    if (harvested > lines.size()) {
      // The file was truncated under us (a `clean` mid-run). Start over rather than mis-slice.
      harvested = 0;
      floor = 0;
      lines = readLines();
    }
    for (String line : lines.subList(harvested, lines.size())) {
      edge(line).ifPresent(EDGES::add);
    }
    harvested = lines.size();
  }

  /**
   * One recorded line as an edge, or nothing when the line carried no frames.
   *
   * <p>This is the class's one exclusion and it is on the recording rather than on the route: an
   * empty poll is the safety net ticking, a carried one is the dependency. Skipping is done <b>at
   * harvest</b> and a skipped line never enters the list, so the framework's per-source cursor still
   * slices a prefix-stable sequence.
   */
  private static Optional<NetworkEdge> edge(String line) {
    // "METHOD URI STATUS carried|empty" — four fields, no quoting, and a URI carries no raw space.
    String[] fields = line.strip().split(" ");
    if (fields.length != 4 || !fields[1].startsWith("/") || !"carried".equals(fields[3])) {
      return Optional.empty();
    }
    return Optional.of(
        NetworkEdge.http(
            StoryTarget.SERVICE,
            SERVICE_NAME,
            Labels.scrub(fields[0] + " " + fields[1] + " -> " + fields[2])));
  }

  /** Everything recorded since the floor — i.e. everything a story could own. */
  private static List<String> readLines() {
    List<String> all = allLines();
    return floor >= all.size() ? List.of() : all.subList(floor, all.size());
  }

  /**
   * The recording's complete lines. A missing file is an empty recording rather than a failure, and
   * an <b>unterminated tail is dropped</b>: the server appends while this reads, and half a line
   * would shape half an edge. The next harvest sees it whole.
   */
  private static List<String> allLines() {
    if (!Files.isRegularFile(ACCESS_LOG)) {
      return List.of();
    }
    String text;
    try {
      text = Files.readString(ACCESS_LOG, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      return List.of();
    }
    int lastComplete = text.lastIndexOf('\n');
    if (lastComplete < 0) {
      return List.of();
    }
    return List.of(text.substring(0, lastComplete).split("\n"));
  }

  // --- the files -------------------------------------------------------------------------------

  private static String armedPage() {
    try {
      return Files.isRegularFile(ARMED) ? Files.readString(ARMED, StandardCharsets.UTF_8) : null;
    } catch (IOException unreadable) {
      return null;
    }
  }

  private static synchronized void record(String method, String uri, int status, boolean carried) {
    try {
      Files.createDirectories(ROOT);
      Files.writeString(
          ACCESS_LOG,
          method + " " + uri + " " + status + " " + (carried ? "carried" : "empty") + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException ignored) {
      // A recording that cannot be written costs the diagram an arrow; it must not cost the
      // launched process its answer, which is what the sweep is actually waiting for.
    }
  }

  private static void wipe() {
    try {
      Files.deleteIfExists(ARMED);
      Files.deleteIfExists(ACCESS_LOG);
    } catch (IOException e) {
      throw new UncheckedIOException("could not clear " + ROOT, e);
    }
  }
}
