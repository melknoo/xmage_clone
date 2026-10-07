package dev.magelite.api;

import dev.magelite.game.GameHost;
import dev.magelite.view.dto.StateDto;
import io.javalin.websocket.WsContext;
import org.apache.log4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sendet Nachrichten an einen WebSocket-Client, ohne den Game-Thread zu blockieren.
 * Mehrere ausstehende {@link StateDto} werden zum neuesten zusammengefasst; alles andere bleibt in Reihenfolge.
 * <p>
 * Verlustbehafteter Modus (Zuschauer): ein neuer State ersetzt jeden noch wartenden aelteren State (nicht nur den
 * letzten), die Queue ist klein ({@link #LOSSY_MAX_QUEUE}); laeuft sie trotzdem ueber, liest der Client nicht mehr mit
 * -> {@code onOverflow} (Verbindung mit 4408 schliessen) statt Speicher zu horten.
 */
final class Outbox implements GameHost.Sink {

    private static final Logger LOG = Logger.getLogger(Outbox.class);
    private static final int MAX_QUEUE = 5000;
    static final int LOSSY_MAX_QUEUE = 256;

    private final WsContext ctx;
    private final Deque<Object> queue = new ArrayDeque<>();
    private final ExecutorService sender;
    private final boolean lossy;
    private final Runnable onOverflow;
    private boolean scheduled;
    private volatile boolean closed;

    Outbox(WsContext ctx) {
        this(ctx, false, null);
    }

    /**
     * @param lossy      Zuschauer-Modus (siehe Klasse)
     * @param onOverflow nur im verlustbehafteten Modus: wird einmal aufgerufen, wenn die Queue ueberlaeuft (nie unter
     *                   der Queue-Sperre, darf nicht blockieren)
     */
    Outbox(WsContext ctx, boolean lossy, Runnable onOverflow) {
        this.ctx = ctx;
        this.lossy = lossy;
        this.onOverflow = onOverflow;
        this.sender = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ws-out " + ctx.sessionId());
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void send(Object message) {
        if (closed) {
            return;
        }
        boolean overflow = false;
        synchronized (queue) {
            if (message instanceof StateDto) {
                if (lossy) {
                    // jeden noch wartenden aelteren State verwerfen
                    for (Iterator<Object> it = queue.iterator(); it.hasNext(); ) {
                        if (it.next() instanceof StateDto) {
                            it.remove();
                        }
                    }
                } else {
                    // aelteren, noch nicht gesendeten State ersetzen (nur wenn er das letzte Element ist)
                    Object last = queue.peekLast();
                    if (last instanceof StateDto) {
                        queue.pollLast();
                    }
                }
            }
            if (lossy && queue.size() >= LOSSY_MAX_QUEUE) {
                overflow = true;
                closed = true;
                queue.clear();
            } else {
                if (queue.size() >= MAX_QUEUE) {
                    LOG.warn("Outbox voll - verwerfe aelteste Nachricht");
                    queue.pollFirst();
                }
                queue.addLast(message);
                if (!scheduled) {
                    scheduled = true;
                    sender.execute(this::drain);
                }
            }
        }
        if (overflow) {
            LOG.warn("Zuschauer liest nicht mit (Queue voll) - Verbindung wird geschlossen");
            sender.shutdown();
            if (onOverflow != null) {
                onOverflow.run();
            }
        }
    }

    private void drain() {
        while (true) {
            Object msg;
            synchronized (queue) {
                msg = queue.pollFirst();
                if (msg == null) {
                    scheduled = false;
                    return;
                }
            }
            try {
                if (!closed && ctx.session.isOpen()) {
                    ctx.send(Json.write(msg));
                }
            } catch (Throwable e) {
                LOG.warn("WebSocket-Senden fehlgeschlagen: " + e);
            }
        }
    }

    void close() {
        closed = true;
        sender.shutdown();
    }
}
