package io.github.rthadani.bff;

import java.util.Map;

/**
 * SPI for supplying a custom outbound HTTP client used by spec {@code url:}
 * steps. Register on {@link BffConfig.Builder#httpClient(BffHttpClient)}.
 *
 * <p>If unset, BFF uses its built-in {@code hato}-based client. Supply one when
 * you need to route calls through your own connection pool, share an SSL
 * context / truststore, or otherwise reuse your application's HTTP stack.
 *
 * <p>Implementations must be thread-safe.
 */
public interface BffHttpClient {

    Response send(Request request);

    /**
     * Immutable HTTP request handed to {@link #send(Request)}.
     *
     * <p>{@code method} is uppercased ({@code "GET"}, {@code "POST"}, ...).
     * {@code queryParams} and {@code headers} may be empty but are never
     * null. {@code body} is null when the step has no body; otherwise it is
     * a Java {@link Map} the client should serialize as JSON.
     */
    final class Request {
        public final String method;
        public final String url;
        public final Map<String, Object> queryParams;
        public final Object body;
        public final Map<String, String> headers;
        public final String stepId;

        public Request(String method,
                       String url,
                       Map<String, Object> queryParams,
                       Object body,
                       Map<String, String> headers,
                       String stepId) {
            this.method      = method;
            this.url         = url;
            this.queryParams = queryParams;
            this.body        = body;
            this.headers     = headers;
            this.stepId      = stepId;
        }
    }

    /**
     * Immutable HTTP response. BFF maps {@code status} to its usual error
     * codes ({@code 401 → :unauthorized}, {@code 404 → :not-found}, etc.).
     * {@code body} is the raw response body as a string (may be null); BFF
     * parses JSON where applicable.
     */
    final class Response {
        public final int status;
        public final String body;

        public Response(int status, String body) {
            this.status = status;
            this.body   = body;
        }
    }
}
