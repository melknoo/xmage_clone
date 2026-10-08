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
 * Sendet Nachrichten an einen Client, ohne den Game-Thread zu blockieren.
 * Mehrere ausstehende {@link StateDto} werden zum neuesten zusammengefasst; alles andere bleibt in Reihenfolge.
 * <p>
 * Der Ausgang ist ein {@link Transport}: ein WebSocket ({@link #Outbox(WsContext)}) oder - fuer das Relay
 * (selbst gehosteter Tisch) - der Host-Link, der fertige JSON-Strings in Umschlaege packt.
 * {@link Raw} transportiert bereits serialisiertes JSON (fly reicht Host-Nachrichten 1:1 durch); ein Raw-State
 * wird wie ein {@link StateDto} zusammengefasst.
 * <p>
 * Verlustbehafteter Modus (Zuschauer): ein neuer State ersetzt jeden noch wartenden aelteren State (nicht nur den
 * letzten), die Queue ist klein ({@link #LOSSY_MAX_QUEUE}); laeuft sie trotzdem ueber, liest der Client nicht mehr mit
 * -> {@code onOverflow} (Verbindung mit 4408 schliessen) statt Speicher zu horten.
 */
public final class Outbox implements GameHost.Sink {

    private static final Logger LOG = Logger.getLogger(Outbox.class);
    private static final int MAX_QUEUE = 5000;
    static final int LOSSY_MAX_QUEUE = 256;

    /** Ausgang fuer fertige JSON-Strings. */
    public interface Transport {
        boolean isOpen();

        void send(String json);
    }

    /** Schon serialisierte Nachricht; {@code state} = ist ein State (wird zusammengefasst). */
    public record Raw(String json, boolean state) {
    }

    private final Transport transport;
    private final Deque<Object> queue = new ArrayDeque<>();
    private final ExecutorService sender;
    private final boolean lossy;
    private final Runnable onOverflow;
    private boolean scheduled;
    private volatile boolean closed;

    public Outbox(WsContext ctx) {
        this(ctx, false, null);
    }

    /**
     * @param lossy      Zuschauer-Modus (siehe Klasse)
     * @param onOverflow nur im verlustbehafteten Modus: wird einmal aufgerufen, wenn die Queue ueberlaeuft (nie unter
     *                   der Queue-Sperre, darf nicht blockieren)
     */
    public Outbox(WsContext ctx, boolean lossy, Runnable onOverflow) {
        this(wsTransport(ctx), "ws-out " + ctx.sessionId(), lossy, onOverflow);
    }

    public Outbox(Transport transport, String threadName) {
        this(transport, threadName, false, null);
    }

    public Outbox(Transport transport, String threadName, boolean lossy, Runnable onOverflow) {
        this.transport = transport;
        this.lossy = lossy;
        this.onOverflow = onOverflow;
        this.sender = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
    }

    private static Transport wsTransport(WsContext ctx) {
        return new Transport() {
            @Override
            public boolean isOpen() {
                return ctx.session.isOpen();
            }

            @Override
            public void send(String json) {
                ctx.send(json);
            }
        };
    }

    private static boolean isState(Object o) {
        return o instanceof StateDto || (o instanceof Raw r && r.state());
    }

    @Override
    public void send(Object message) {
        if (closed) {
            return;
        }
        boolean overflow = false;
        synchronized (queue) {
            if (isState(message)) {
                if (lossy) {
                    // jeden noch wartenden aelteren State verwerfen
                    for (Iterator<Object> it = queue.iterator(); it.hasNext(); ) {
                        if (isState(it.next())) {
                            it.remove();
                        }
                    }
                } else {
                    // aelteren, noch nicht gesendeten State ersetzen (nur wenn er das letzte Element ist)
                    Object last = queue.peekLast();
                    if (isState(last)) {
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
                if (!closed && transport.isOpen()) {
                    transport.send(msg instanceof Raw r ? r.json() : Json.write(msg));
                }
            } catch (Throwable e) {
                LOG.warn("Senden fehlgeschlagen: " + e);
            }
        }
    }

    public void close() {
        closed = true;
        sender.shutdown();
    }
}
