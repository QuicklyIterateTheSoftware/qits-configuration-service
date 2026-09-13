package eu.wohlben.qits.configuration.api;

import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticator;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Gives the 401 and the 403 of the security layer the same {@code {"message": "..."}} body as every
 * other refusal here.
 *
 * <p>Quarkus REST's own mappers answer both with an empty body. The client shows the message to a
 * person, so an empty body leaves them with nothing to read.
 *
 * <p>The 401 keeps the challenge of the authentication mechanism: its status and its headers, for
 * example {@code WWW-Authenticate: Bearer} when the machine track is on. Only the body is added.
 */
public class SecurityRefusalMapper {

  @ServerExceptionMapper
  public Uni<Response> unauthorized(UnauthorizedException exception, RoutingContext context) {
    String message = messageOf(exception, Response.Status.UNAUTHORIZED);
    HttpAuthenticator authenticator = context.get(HttpAuthenticator.class.getName());
    if (authenticator == null) {
      return Uni.createFrom().item(refusal(new ChallengeData(401), message));
    }
    return authenticator
        .getChallenge(context)
        .map(challenge -> refusal(challenge, message))
        .onFailure()
        .recoverWithItem(() -> refusal(new ChallengeData(401), message));
  }

  @ServerExceptionMapper
  public Response forbidden(ForbiddenException exception) {
    return refusal(new ChallengeData(403), messageOf(exception, Response.Status.FORBIDDEN));
  }

  private static Response refusal(ChallengeData challenge, String message) {
    Response.ResponseBuilder response = Response.status(challenge.status);
    challenge.getHeaders().forEach((name, value) -> response.header(name.toString(), value));
    return response.entity(Map.of("message", message)).type(MediaType.APPLICATION_JSON).build();
  }

  private static String messageOf(RuntimeException exception, Response.Status status) {
    String message = exception.getMessage();
    return message == null || message.isBlank() ? status.getReasonPhrase() : message;
  }
}
