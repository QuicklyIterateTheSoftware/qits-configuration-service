package eu.wohlben.qits.configuration.stories.support;

/**
 * The one launched process, addressed the way each of its surfaces is addressed — and named the way
 * a diagram names it.
 *
 * <p>Everything qits-configuration serves to a machine hangs off <b>one segment</b>. {@code
 * quarkus.rest.path=/configuration/api} is the JSON API; {@code
 * quarkus.http.non-application-root-path=/configuration/q} is what Quarkus itself serves, and the
 * framework's shipped RestAssured tap skips any path carrying a {@code /q/} segment — which is
 * exactly right here, so no story class overrides the predicate. The segment is not decoration: the
 * platform edge path-routes every application's segment verbatim on every host, and the client is
 * served at this service's ROOT, so a route outside {@code /configuration} is one the SPA's
 * catch-all answers with a page.
 *
 * <p>The <b>port is random</b> — failsafe launches the artifact with {@code
 * quarkus.http.test-port=0} — so nothing here is a constant except the paths. RestAssured is
 * configured with the port by the Quarkus integration-test extension, so an API call needs no base
 * url at all; only {@link StoryPlatform}'s tap-invisible fixture client builds one, and it reads
 * {@code RestAssured.port} for exactly that reason.
 *
 * <p><b>Every application name a story uses is a stable literal</b>, never a run stamp. A name is a
 * whole path segment and {@link eu.wohlben.qits.userflows.Labels} rewrites only segments it can tell
 * were generated (a uuid, a long hex run, a bare number) — {@code story-import-alpha} is none of
 * those and would survive into the label exactly as written. A stamped name would therefore move
 * every {@code networkHash} on every run. The suite can afford literals because each story class
 * owns its own names and the embedded postgres is new per run.
 */
public final class StoryTarget {

  /** How every diagram in this catalogue names the service under test, on both sides of an edge. */
  public static final String SERVICE = "qits-configuration";

  /** {@code /configuration/api} — {@code quarkus.rest.path}. A resource's {@code @Path} is relative. */
  public static final String API_PATH = "/configuration/api";

  /** The applications overview: every configured application, its entry count and head revision. */
  public static final String APPLICATIONS_PATH = API_PATH + "/applications";

  /** The bulk import: {@code text/plain}, an extras properties file whole. */
  public static final String IMPORT_PATH = API_PATH + "/import";

  private StoryTarget() {}

  /** One application's own subtree. Not a route of its own; the three below hang off it. */
  public static String applicationPath(String application) {
    return APPLICATIONS_PATH + "/" + application;
  }

  /**
   * <b>The deployer's read.</b> One application's configuration as a flat map at the full {@code
   * qits.platform.deployments.extras.<app>.<key>} spelling, plus the revision it was read at.
   */
  public static String resolvedPath(String application) {
    return applicationPath(application) + "/resolved";
  }

  /** One application's current entries, by key — the editor's read. */
  public static String entriesPath(String application) {
    return applicationPath(application) + "/entries";
  }

  /** One entry: {@code PUT} sets it, {@code DELETE} removes it and keeps it in the history. */
  public static String entryPath(String application, String key) {
    return entriesPath(application) + "/" + key;
  }

  /** One application's whole write history, newest first. Deletions are in it, with a null value. */
  public static String historyPath(String application) {
    return applicationPath(application) + "/history";
  }
}
