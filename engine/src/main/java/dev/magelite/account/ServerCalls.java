package dev.magelite.account;

import dev.magelite.api.Auth;
import dev.magelite.relay.HostLinks;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * REST-Aufrufe der lokalen Engine am Online-Server mit der Session des Nutzers (Cookie {@code ml_sess}). Ein
 * gemeinsamer {@link HttpClient} (HTTP/1.1: Dev-Server laufen ohne TLS, Javalin spricht dort kein h2c).
 * {@code passive}: Hintergrundabruf, haelt fly nicht wach ({@link Auth#PASSIVE_HEADER}).
 */
public final class ServerCalls {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private ServerCalls() {
    }

    /**
     * @param body JSON oder null (dann ohne Body)
     * @throws IOException Server nicht erreichbar (inkl. Timeout)
     */
    public static HttpResponse<byte[]> send(String server, String session, String method, String pathAndQuery, byte[] body,
                                            String contentType, boolean passive, Duration timeout) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(server + pathAndQuery))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header(HostLinks.ENGINE_HEADER, HostLinks.ENGINE_MARKER);
        if (session != null) {
            b.header("Cookie", Auth.SESSION_COOKIE + "=" + session);
        }
        if (passive) {
            b.header(Auth.PASSIVE_HEADER, "1");
        }
        if (body != null) {
            b.header("Content-Type", contentType == null ? "application/json" : contentType);
            b.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("unterbrochen", e);
        }
    }

    /** JSON-Aufruf, Antwort als Text. */
    public static HttpResponse<String> json(String server, String session, String method, String path, String jsonBody,
                                            boolean passive, Duration timeout) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(server + path))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header(HostLinks.ENGINE_HEADER, HostLinks.ENGINE_MARKER);
        if (session != null) {
            b.header("Cookie", Auth.SESSION_COOKIE + "=" + session);
        }
        if (passive) {
            b.header(Auth.PASSIVE_HEADER, "1");
        }
        if (jsonBody != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("unterbrochen", e);
        }
    }

    /** Basis-URL pruefen und normalisieren ({@code http(s)://host[:port]}, ohne Slash am Ende). */
    public static String normalizeServer(String server) {
        String base = server == null ? "" : server.strip().replaceAll("/+$", "");
        URI u;
        try {
            u = URI.create(base);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Server-URL: http(s)://host[:port]");
        }
        if (u.getScheme() == null || !(u.getScheme().equals("http") || u.getScheme().equals("https")) || u.getHost() == null
                || (u.getPath() != null && !u.getPath().isEmpty())) {
            throw new IllegalArgumentException("Server-URL: http(s)://host[:port]");
        }
        return base;
    }
}
