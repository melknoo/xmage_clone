package dev.magelite.admin;

import dev.magelite.api.Auth;
import dev.magelite.api.HttpServer;
import dev.magelite.auth.User;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import java.util.Map;

/**
 * Admin-Bereich (nur Server-Modus, nur Admins): Nutzerliste, Nutzer-Detail, Server-Uebersicht und Eingriffe.
 * Die Einladungs-Verwaltung ({@code /api/admin/invites}) liegt weiter in {@code AuthRoutes}.
 */
public final class AdminRoutes implements HttpServer.Module {

    private final AdminService admin;

    public AdminRoutes(AdminService admin) {
        this.admin = admin;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/admin/users", ctx -> {
            Auth.requireAdmin(ctx);
            ctx.json(admin.users());
        });
        app.get("/api/admin/users/{id}", ctx -> {
            Auth.requireAdmin(ctx);
            long id = Long.parseLong(ctx.pathParam("id"));
            var detail = admin.user(id);
            if (detail.isEmpty()) {
                notFound(ctx, "Konto nicht gefunden");
                return;
            }
            ctx.json(detail.get());
        });
        app.post("/api/admin/users/{id}/logout", ctx -> {
            User me = Auth.requireAdmin(ctx);
            long id = Long.parseLong(ctx.pathParam("id"));
            if (id == me.id()) {
                throw new IllegalArgumentException("Das eigene Konto kann nicht abgemeldet werden");
            }
            ctx.json(Map.of("ok", admin.logout(id)));
        });
        app.get("/api/admin/server", ctx -> {
            Auth.requireAdmin(ctx);
            ctx.json(admin.server());
        });
        app.post("/api/admin/games/{id}/abort", ctx -> {
            Auth.requireAdmin(ctx);
            if (!admin.abortGame(ctx.pathParam("id"))) {
                notFound(ctx, "Spiel läuft nicht mehr");
                return;
            }
            ctx.json(Map.of("ok", true));
        });
        app.delete("/api/admin/tables/{id}", ctx -> {
            Auth.requireAdmin(ctx);
            if (!admin.closeTable(ctx.pathParam("id"))) {
                notFound(ctx, "Diesen Tisch gibt es nicht mehr");
                return;
            }
            ctx.json(Map.of("ok", true));
        });
    }

    /** 404 im Format der uebrigen Fehler ({@code {error}}), damit die UI den Text anzeigt. */
    private static void notFound(Context ctx, String message) {
        ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", message));
    }
}
