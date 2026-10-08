package dev.magelite.auth;

import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.api.Json;
import org.apache.log4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Cloudflare Turnstile (Captcha) serverseitig pruefen. Ohne Secret (Dev/e2e) gilt jede Antwort als gueltig; im
 * Betrieb ohne Secret bleibt die Registrierung geschlossen (siehe {@code SignupService}).
 */
public final class Turnstile {

    private static final Logger LOG = Logger.getLogger(Turnstile.class);
    private static final URI VERIFY = URI.create("https://challenges.cloudflare.com/turnstile/v0/siteverify");

    private final String secret;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** @param secret null/leer = Pruefung aus */
    public Turnstile(String secret) {
        this.secret = secret == null || secret.isBlank() ? null : secret.strip();
    }

    public boolean enabled() {
        return secret != null;
    }

    public boolean verify(String response, String remoteIp) {
        if (secret == null) {
            return true;
        }
        if (response == null || response.isBlank() || response.length() > 4096) {
            return false;
        }
        String form = "secret=" + enc(secret) + "&response=" + enc(response) + (remoteIp == null ? "" : "&remoteip=" + enc(remoteIp));
        try {
            HttpRequest req = HttpRequest.newBuilder(VERIFY)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode n = Json.MAPPER.readTree(res.body());
            boolean ok = n.path("success").asBoolean(false);
            if (!ok) {
                LOG.info("Captcha abgelehnt: " + n.path("error-codes"));
            }
            return ok;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            LOG.warn("Captcha-Pruefung fehlgeschlagen: " + e);
            return false;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
