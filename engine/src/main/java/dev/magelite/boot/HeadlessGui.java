package dev.magelite.boot;

import forge.gamemodes.match.HostedMatch;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;
import forge.util.FSerializableFunction;
import forge.util.ImageFetcher;
import forge.util.ThreadUtil;
import org.apache.log4j.Logger;
import org.jupnp.UpnpServiceConfiguration;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Forge-{@link IGuiBase} ohne Oberflaeche (ersetzt Forges Swing-/libGDX-Frontend).
 * <p>
 * EDT-Modell: Es gibt keinen echten Event-Dispatch-Thread. {@code invokeInEdtNow/AndWait} laufen inline mit gesetztem
 * {@link #inEdt}-Flag ({@link #isGuiThread()} liefert es). {@code invokeInEdtLater} laeuft inline, wenn der Aufrufer
 * schon "im EDT" oder ein Forge-Spiel-Thread ({@code Game*}) ist; von fremden Threads (z. B. Forges
 * {@code awaitNextInput}-Timer) geht er an {@link #runLaterFromForeignThread}, standardmaessig einen seriellen
 * Daemon-Thread {@code forge-edt}. Der Spiel-Kern kann das ueberschreiben (z. B. in die inbox des Spiels einreihen).
 * <p>
 * Blockierende Dialoge gehoeren ins {@code IGuiGame} des jeweiligen Sitzes; landet ein Dialog hier, ist das ein
 * nicht abgebildeter Pfad: lautes Log + {@link IllegalStateException}.
 */
public class HeadlessGui implements IGuiBase {

    private static final Logger LOG = Logger.getLogger(HeadlessGui.class);

    private static final ThreadLocal<Boolean> inEdt = ThreadLocal.withInitial(() -> false);

    private final String assetsDir;
    private final String version;
    private volatile Thread edtThread;
    private final ExecutorService edt = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "forge-edt");
        t.setDaemon(true);
        edtThread = t;
        return t;
    });

    /**
     * @param forgeHome Verzeichnis mit {@code res/} und {@code forge.profile.properties}
     * @param version   Forge-Version (aus {@code manifest.json})
     */
    public HeadlessGui(File forgeHome, String version) {
        this.assetsDir = forgeHome.getAbsolutePath() + File.separator;
        this.version = version;
    }

    // ---- EDT -----------------------------------------------------------------------------------------------------

    @Override
    public boolean isGuiThread() {
        return inEdt.get() || Thread.currentThread() == edtThread;
    }

    @Override
    public void invokeInEdtNow(Runnable r) {
        runAsEdt(r);
    }

    @Override
    public void invokeInEdtAndWait(Runnable r) {
        runAsEdt(r);
    }

    @Override
    public void invokeInEdtLater(Runnable r) {
        if (isGuiThread() || ThreadUtil.isGameThread()) {
            runAsEdt(r);
        } else {
            runLaterFromForeignThread(r);
        }
    }

    /** Ziel fuer {@code invokeInEdtLater} von Threads, die weder EDT noch Spiel-Thread sind. */
    protected void runLaterFromForeignThread(Runnable r) {
        edt.execute(() -> runAsEdt(r));
    }

    protected static void runAsEdt(Runnable r) {
        if (inEdt.get()) {
            r.run();
            return;
        }
        inEdt.set(true);
        try {
            r.run();
        } finally {
            inEdt.set(false);
        }
    }

    @Override
    public void runBackgroundTask(String message, Runnable task) {
        Thread t = new Thread(task, "forge-bg");
        t.setDaemon(true);
        t.start();
    }

    // ---- Umgebung ------------------------------------------------------------------------------------------------

    @Override
    public boolean isRunningOnDesktop() {
        return true;
    }

    @Override
    public boolean isLibgdxPort() {
        return false;
    }

    @Override
    public String getCurrentVersion() {
        return version;
    }

    @Override
    public String getAssetsDir() {
        return assetsDir;
    }

    @Override
    public float getScreenScale() {
        return 1f;
    }

    @Override
    public void preventSystemSleep(boolean preventSleep) {
    }

    @Override
    public UpnpServiceConfiguration getUpnpPlatformService() {
        return null;
    }

    @Override
    public boolean hasNetGame() {
        return false;
    }

    // ---- Bilder / Skin / Ton: MageLite zeigt Bilder selbst (Scryfall) ---------------------------------------------

    @Override
    public ImageFetcher getImageFetcher() {
        return null;
    }

    @Override
    public ISkinImage getSkinIcon(FSkinProp skinProp) {
        return null;
    }

    @Override
    public ISkinImage getUnskinnedIcon(String path) {
        return null;
    }

    @Override
    public ISkinImage getCardArt(PaperCard card, boolean backFace) {
        return null;
    }

    @Override
    public ISkinImage createLayeredImage(PaperCard card, FSkinProp background, String overlayFilename, float opacity) {
        return null;
    }

    @Override
    public void clearImageCache() {
    }

    @Override
    public String encodeSymbols(String str, boolean formatReminderText) {
        return str;
    }

    @Override
    public int getAvatarCount() {
        return 0;
    }

    @Override
    public int getSleevesCount() {
        return 0;
    }

    @Override
    public boolean isSupportedAudioFormat(File file) {
        return false;
    }

    @Override
    public IAudioClip createAudioClip(String filename) {
        return null;
    }

    @Override
    public IAudioMusic createAudioMusic(String filename) {
        return null;
    }

    @Override
    public void startAltSoundSystem(String filename, boolean isSynchronized) {
    }

    // ---- Nicht-blockierende Anzeigen: protokollieren --------------------------------------------------------------

    @Override
    public void showBugReportDialog(String title, String text, boolean showExitAppBtn) {
        LOG.error("Forge-Fehlerbericht: " + title + "\n" + text);
    }

    @Override
    public void download(GuiDownloadService service, Consumer<Boolean> callback) {
        LOG.warn("Forge wollte herunterladen (" + service.getClass().getSimpleName() + ") - abgelehnt");
        if (callback != null) {
            callback.accept(false);
        }
    }

    @Override
    public void copyToClipboard(String text) {
    }

    @Override
    public void browseToUrl(String url) {
    }

    @Override
    public void showCardList(String title, String message, List<PaperCard> list) {
    }

    @Override
    public boolean showBoxedProduct(String title, String message, List<PaperCard> list) {
        return false;
    }

    @Override
    public void showImageDialog(ISkinImage image, String message, String title) {
    }

    @Override
    public void showSpellShop() {
    }

    @Override
    public void showBazaar() {
    }

    // ---- Blockierende Dialoge / Match-Fabriken: gehoeren nicht hierher ----------------------------------------------

    @Override
    public int showOptionDialog(String message, String title, FSkinProp icon, List<String> options, int defaultOption) {
        throw unmapped("showOptionDialog", message);
    }

    @Override
    public String showInputDialog(String message, String title, FSkinProp icon, String initialInput,
                                  List<String> inputOptions, boolean isNumeric) {
        throw unmapped("showInputDialog", message);
    }

    @Override
    public String showFileDialog(String title, String defaultDir) {
        throw unmapped("showFileDialog", title);
    }

    @Override
    public File getSaveFile(File defaultFile) {
        throw unmapped("getSaveFile", String.valueOf(defaultFile));
    }

    @Override
    public <T> List<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax,
                             List<T> sourceChoices, List<T> destChoices) {
        throw unmapped("order", title);
    }

    @Override
    public <T> List<T> getChoices(String message, int min, int max, Collection<T> choices, Collection<T> selected,
                                  FSerializableFunction<T, String> display) {
        throw unmapped("getChoices", message);
    }

    @Override
    public PaperCard chooseCard(String title, String message, List<PaperCard> list) {
        throw unmapped("chooseCard", title);
    }

    @Override
    public IGuiGame getNewGuiGame() {
        throw unmapped("getNewGuiGame", null);
    }

    @Override
    public HostedMatch hostMatch() {
        throw unmapped("hostMatch", null);
    }

    private static IllegalStateException unmapped(String method, String detail) {
        IllegalStateException e = new IllegalStateException("HeadlessGui." + method + " ist nicht abgebildet"
                + (detail == null ? "" : ": " + detail));
        LOG.error("UNMAPPED HeadlessGui." + method + " auf Thread " + Thread.currentThread().getName(), e);
        return e;
    }
}
