package eu.wohlben.qits.configuration.stories.support;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.restassured.specification.RequestSpecification;

/**
 * The two identity tracks qits-configuration accepts, one helper each — because a story that
 * presented the wrong one would be documenting a door that does not exist.
 *
 * <h2>A machine is a bearer</h2>
 *
 * <p>{@link #platformService(RequestSpecification, String)} presents an RS256 token minted by {@link
 * MockIdp} against the very JWKS the launched process fetched at startup: {@code
 * aud=qits-configuration} (what {@code qits.auth.machine.audience} pins as a literal in {@code
 * application.properties}) and {@code groups=[qits:system]} — qits-platform-idp copies a client's
 * roles into that claim and quarkus-oidc reads it as roles with no configuration at all.
 *
 * <h2>A person is a pair of headers</h2>
 *
 * <p>{@link #person(RequestSpecification, String)} sends {@code X-Qits-User} and {@code
 * X-Qits-Roles} instead, which is what the platform edge asserts for a logged-in human: this service
 * authenticates no person itself, and the edge strips every client-supplied {@code X-Qits-*} header
 * from every inbound request, which is the entire reason a header can be trusted as an identity
 * here. It is not a shortcut around the bearer — the OIDC tenant is {@code
 * application-type=service}, i.e. <b>bearer-only</b>, so a request carrying no {@code Authorization}
 * header is never challenged by it and falls through to the header mechanism exactly as it does
 * behind the edge.
 *
 * <p><b>The name in that header is not decoration: it is the audit trail.</b>
 * {@code ForwardAuthIdentityProvider} makes {@code X-Qits-User} the principal, and
 * {@code ConfigurationController.actor()} records the principal on every revision — so the person
 * who typed a value is the person the history names. The bearer track is the same story with the
 * token's {@code sub}, which is why {@link #platformToken(String)} takes one.
 *
 * <p><b>The synthetic {@code %test} dev user is not available here, and that is the point.</b>
 * {@code qits.auth.forward.dev-user} is {@code %test}-scoped and {@code LaunchMode}-guarded, and a
 * launched artifact runs in {@code NORMAL} mode — so an anonymous request really is anonymous and
 * the roles below are the only thing opening these doors. Every refusal in {@code stories.refusals}
 * is a claim only a packaged run can make: inside a {@code @QuarkusTest} the dev identity holds all
 * four platform roles and no route here would refuse anybody.
 */
public final class StoryIdentities {

  /**
   * The audience this service enforces. A literal, because {@code application.properties} pins
   * {@code qits.auth.machine.audience=qits-configuration} as one — there is no environment variable
   * to feed, and overriding it would only test a string this suite invented.
   */
  public static final String AUDIENCE = "qits-configuration";

  /** The machine role a platform peer holds: the deployer's resolved read, the bootstrap's import. */
  public static final String MACHINE_ROLE = "qits:system";

  /** The human role the edge asserts for an authenticated admin session. */
  public static final String HUMAN_ROLE = "qits:admin";

  /** A role a signed-in person may well hold and which opens nothing here. */
  public static final String READER_ROLE = "qits:user";

  /** The header the edge names the logged-in person in. */
  public static final String USER_HEADER = "X-Qits-User";

  /** The header the edge asserts that person's roles in, comma-separated. */
  public static final String ROLES_HEADER = "X-Qits-Roles";

  private StoryIdentities() {}

  /**
   * A platform peer's bearer.
   *
   * <p>Minted fresh per call rather than cached: a token is a credential, and a helper that handed
   * the same string to two stories would make {@link
   * eu.wohlben.qits.userflows.report.ReportAssertions#assertNotLeaked} a weaker claim than it reads
   * as. The {@code sub} names which peer, because the revision this service writes records it.
   *
   * <p>Minting is local crypto against the parked keypair — it makes no request to the mock at all,
   * which is why no story's diagram carries an arrow for getting a token.
   */
  public static String platformToken(String subject) {
    return MockIdp.attach()
        .token()
        .subject(subject)
        .audience(AUDIENCE)
        .groups(MACHINE_ROLE)
        .mint();
  }

  /** {@code given()} with a platform peer's bearer on it. */
  public static RequestSpecification platformService(RequestSpecification request, String subject) {
    return request.header("Authorization", "Bearer " + platformToken(subject));
  }

  /** {@code given()} with the two headers the edge asserts for a logged-in admin session. */
  public static RequestSpecification person(RequestSpecification request, String user) {
    return person(request, user, HUMAN_ROLE);
  }

  /** …and the same pair for a session holding some other role, which is how a 403 is asked for. */
  public static RequestSpecification person(
      RequestSpecification request, String user, String roles) {
    return request.header(USER_HEADER, user).header(ROLES_HEADER, roles);
  }
}
