package dev.magelite.relay;

import dev.magelite.api.Outbox;
import dev.magelite.game.GameHost;
import dev.magelite.view.dto.Messages;

import java.util.UUID;

/**
 * Ausgang eines Sitzes eines Relay-Spiels: Nachrichten des {@link GameHost} werden serialisiert und als
 * {@code out}-Umschlag ueber den Host-Link an fly geschickt, das sie dem Spieler-Socket zustellt. Eine eigene
 * {@link Outbox} je Sitz fasst States zusammen wie beim direkten WebSocket.
 * <p>
 * {@link Messages.GameOver} wird nie durchgereicht (auch nicht das Replay in {@code attach}): fly baut das gameOver
 * mit Belohnung selbst aus {@code finished}.
 */
final class RelaySink implements GameHost.Sink {

    private final Outbox outbox;

    RelaySink(HostLinkClient link, UUID gameId, long userId) {
        this.outbox = new Outbox(new Outbox.Transport() {
            @Override
            public boolean isOpen() {
                return link.isOpen();
            }

            @Override
            public void send(String json) {
                link.sendOut(gameId, userId, json);
            }
        }, "relay-out " + userId);
    }

    @Override
    public void send(Object message) {
        if (message instanceof Messages.GameOver) {
            return;
        }
        outbox.send(message);
    }

    void close() {
        outbox.close();
    }
}
