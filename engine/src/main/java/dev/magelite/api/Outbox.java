package dev.magelite.api;

import dev.magelite.game.GameHost;
import dev.magelite.view.dto.StateDto;
import io.javalin.websocket.WsContext;
import org.apache.log4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sendet Nachrichten an einen WebSocket-Client, ohne den Game-Thread zu blockieren.
 * Mehrere ausstehende {@link StateDto} werden zum neuesten zusammengefasst; alles andere bleibt in Reihenfolge.
 */
final class Outbox implements GameHost.Sink {

    private static final Logger LOG = Logger.getLogger(Outbox.class);
    private static final int MAX_QUEUE = 5000;

    private final WsContext ctx;
    private final Deque<Object> queue = new ArrayDeque<>();
    private final ExecutorService sender;
    private boolean scheduled;
    private volatile boolean closed;

    Outbox(WsContext ctx) {
        this.ctx = ctx;
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
        synchronized (queue) {
            if (message instanceof StateDto) {
                // aelteren, noch nicht gesendeten State ersetzen (nur wenn er das letzte Element ist)
                Object last = queue.peekLast();
                if (last instanceof StateDto) {
                    queue.pollLast();
                }
            }
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
