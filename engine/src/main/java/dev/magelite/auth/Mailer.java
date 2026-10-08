package dev.magelite.auth;

import dev.magelite.api.Json;
import org.apache.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versand von Bestaetigungs-/Reset-Mails (Server-Modus). {@link Brevo} im Betrieb (EU, kostenloses Kontingent),
 * {@link Outbox} fuer Dev/e2e (schreibt die Mails als JSON-Dateien, die Tests lesen den Link daraus).
 */
public interface Mailer {

    /** @throws IllegalStateException wenn der Versand fehlschlaegt */
    void send(String to, String subject, String text);

    /** Brevo Transactional API ({@code POST /v3/smtp/email}). */
    final class Brevo implements Mailer {

        private static final Logger LOG = Logger.getLogger(Brevo.class);
        private static final URI API = URI.create("https://api.brevo.com/v3/smtp/email");

        private final String apiKey;
        private final String fromEmail;
        private final String fromName;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        public Brevo(String apiKey, String fromEmail, String fromName) {
            this.apiKey = apiKey;
            this.fromEmail = fromEmail;
            this.fromName = fromName;
        }

        @Override
        public void send(String to, String subject, String text) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sender", Map.of("email", fromEmail, "name", fromName));
            body.put("to", List.of(Map.of("email", to)));
            body.put("subject", subject);
            body.put("textContent", text);
            try {
                HttpRequest req = HttpRequest.newBuilder(API)
                        .timeout(Duration.ofSeconds(20))
                        .header("api-key", apiKey)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                        .build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() / 100 != 2) {
                    LOG.warn("Brevo HTTP " + res.statusCode() + ": " + res.body());
                    throw new IllegalStateException("Mailversand fehlgeschlagen (HTTP " + res.statusCode() + ")");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Mailversand abgebrochen");
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Mailversand fehlgeschlagen: " + e.getMessage());
            }
        }
    }

    /** Dev/e2e: jede Mail als {@code <dir>/<zeit>-<n>.json} ({@code to, subject, text}). */
    final class Outbox implements Mailer {

        private static final Logger LOG = Logger.getLogger(Outbox.class);

        private final Path dir;
        private int n;

        public Outbox(Path dir) {
            this.dir = dir;
        }

        @Override
        public synchronized void send(String to, String subject, String text) {
            try {
                Files.createDirectories(dir);
                Path f = dir.resolve(System.currentTimeMillis() + "-" + (n++) + ".json");
                Files.writeString(f, Json.write(Map.of("to", to, "subject", subject, "text", text)), StandardCharsets.UTF_8);
                LOG.info("Mail an " + to + " (" + subject + ") -> " + f);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Mail nicht geschrieben: " + e.getMessage());
            }
        }
    }
}
