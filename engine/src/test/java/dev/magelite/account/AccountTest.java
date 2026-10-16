package dev.magelite.account;

import dev.magelite.stats.Db;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountTest {

    @Test
    void proxyDropsLocalTokenFromQuery() {
        assertEquals("", AccountProxy.query(null));
        assertEquals("", AccountProxy.query(""));
        assertEquals("", AccountProxy.query("token=abc"));
        assertEquals("?limit=60", AccountProxy.query("limit=60&token=abc"));
        assertEquals("?limit=60&x=1", AccountProxy.query("token=abc&limit=60&x=1"));
        // nur der Schluessel "token" faellt weg, nicht Werte oder aehnliche Namen
        assertEquals("?mytoken=1&q=token", AccountProxy.query("mytoken=1&q=token"));
        assertEquals("?a=1", AccountProxy.query("to%6Ben=zz&a=1"));
    }

    @Test
    void serverUrlIsNormalized() {
        assertEquals("https://magelite.fly.dev", ServerCalls.normalizeServer("https://magelite.fly.dev/"));
        assertEquals("http://127.0.0.1:7431", ServerCalls.normalizeServer(" http://127.0.0.1:7431 "));
        assertThrows(IllegalArgumentException.class, () -> ServerCalls.normalizeServer("ftp://x"));
        assertThrows(IllegalArgumentException.class, () -> ServerCalls.normalizeServer("https://x/pfad"));
        assertThrows(IllegalArgumentException.class, () -> ServerCalls.normalizeServer(null));
    }

    @Test
    void linkStates() throws Exception {
        AccountLink off = new AccountLink(false);
        assertEquals(AccountLink.State.DISABLED, off.state());
        assertTrue(off.local());
        assertThrows(IllegalStateException.class, () -> off.sync("https://x", "s", false));

        AccountLink link = new AccountLink(true);
        assertEquals(AccountLink.State.CHECKING, link.state());
        assertFalse(link.local());
        link.sync("http://127.0.0.1:1", null, false);
        assertEquals(AccountLink.State.NONE, link.state());
        link.sync("http://127.0.0.1:1", null, true);
        assertEquals(AccountLink.State.OFFLINE, link.state());
        assertTrue(link.local());
        assertNull(link.user());

        // Session gegen einen Port ohne Server: pruefen -> nicht erreichbar
        link.sync("http://127.0.0.1:1", "sess", false);
        long t0 = System.currentTimeMillis();
        while (link.state() == AccountLink.State.CHECKING && System.currentTimeMillis() - t0 < 15_000) {
            Thread.sleep(50);
        }
        assertEquals(AccountLink.State.UNREACHABLE, link.state());
        assertFalse(link.active());
        link.goOffline();
        assertEquals(AccountLink.State.OFFLINE, link.state());
    }

    @Test
    void storeKeepsQueuePerAccount(@TempDir Path dir) throws Exception {
        Db db = new Db(dir.resolve("t.db"));
        try {
            AccountStore store = new AccountStore(db);
            String a = AccountStore.scope("https://s", 7) + "outbox|";
            String b = AccountStore.scope("https://s", 8) + "outbox|";
            store.put(a + "g1", "{}");
            store.put(a + "g2", "{\"x\":1}");
            store.put(b + "g3", "{}");
            Map<String, String> mine = store.withPrefix(a);
            assertEquals(List.of(a + "g1", a + "g2"), List.copyOf(mine.keySet()));
            store.delete(a + "g1");
            assertEquals(1, store.withPrefix(a).size());
            assertEquals(1, store.withPrefix(b).size());
            assertEquals("{\"x\":1}", store.get(a + "g2").orElseThrow());
            assertTrue(store.get(a + "g1").isEmpty());
        } finally {
            db.close();
        }
    }
}
