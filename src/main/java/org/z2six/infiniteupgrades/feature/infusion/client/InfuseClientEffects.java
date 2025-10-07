// File: src/main/java/org/z2six/infiniteupgrades/feature/infusion/client/InfuseClientEffects.java
package org.z2six.infiniteupgrades.feature.infusion.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.core.config.UpgradeClientConfig;
import org.z2six.infiniteupgrades.core.registry.ModSounds;
import org.z2six.infiniteupgrades.feature.infusion.client.screen.AngelDemonScreen;
import org.z2six.infiniteupgrades.feature.infusion.client.screen.view.MainGuiView;
import org.z2six.infiniteupgrades.feature.infusion.logic.RitualType;

/**
 * File: src/main/java/org/z2six/infiniteupgrades/feature/infusion/client/InfuseClientEffects.java
 *
 * Client-global infusion effects:
 * - Plays SFX exactly when the fill finishes (flashing begins).
 *   If the GUI is closed at that moment, we backlog the effect and play it upon next GUI open.
 * - Renders a 6-frame 100x100 overlay animation (success/fail; angel/demon) aligned to the GUI output slot.
 *
 * Rendering:
 * - If the Angel/Demon screen is open, position relative to it.
 * - If closed, render where the group (305x222) would be if centered on the current GUI scale.
 *
 * ClientSetup registers:
 *   - ClientTickEvent.Post        -> InfuseClientEffects.clientTick()
 *   - RenderGuiEvent.Post         -> InfuseClientEffects.onRenderGuiPost(gg)
 *   - ScreenEvent.Render.Post     -> InfuseClientEffects.onRenderGuiPost(gg)  (to draw above screens)
 */
public final class InfuseClientEffects {
    private static final Logger LOG = LogUtils.getLogger();

    // ---- Timing from server (authoritative) ----
    private static long endGameTime = -1L;
    private static int  durationTicks = 0;

    // ---- Outcome flag (arrives via early outcome) ----
    private static boolean outcomeKnown = false;
    private static boolean willSucceed = false;

    // ---- Ritual side for selecting spritesheet (arrives with InfuseStartedS2C) ----
    private static RitualType ritualSide = RitualType.ANGEL;

    // ---- SFX gating ----
    private static boolean soundPlayed = false;

    // ---- Animation state ----
    private static boolean animActive = false;
    private static long    animStartGameTime = 0L;

    // If the server final result arrives mid-animation, we defer reset until the animation ends
    private static boolean deferResetUntilAnimEnds = false;

    // ---- Backlog if GUI is closed at fire time ----
    private static boolean pendingBacklog = false;
    private static boolean pendingWillSucceed = false;
    private static RitualType pendingRitualSide = RitualType.ANGEL;

    // Sprite geometry (assets/infiniteupgrades/textures/gui/container/infuse_anims/*.png)
    private static final int FRAME_W = 100;
    private static final int FRAME_H = 100;
    private static final int FRAMES  = 6;
    private static final int SHEET_W = FRAME_W * FRAMES; // 600
    private static final int SHEET_H = FRAME_H;          // 100

    // Animation pacing (ticks/frame)
    private static final int TICKS_PER_FRAME = 3; // total 18 ticks ~= 0.9s

    // Group dimensions and output-slot offset relative to group top-left
    private static final int GROUP_W = 305; // main(176) + 1 + details(128)
    private static final int GROUP_H = 222;

    // Position relative to the group's top-left.
    // Include MainGuiView's +1,+1 draw fudge, then apply requested nudge: 2px left, 6px up.
    private static final int REL_X = MainGuiView.mainDrawDx() + 40 - 2; // was +40 → now +38
    private static final int REL_Y = MainGuiView.mainDrawDy() + 33 - 6; // was +33 → now +27

    private InfuseClientEffects() {}

    // ------------------------------------------------------------------------
    // Lifecycle hooks (called from networking)
    // ------------------------------------------------------------------------

    /** Called on S2C InfuseStarted (now includes ritual side). */
    public static void onInfuseStarted(long endTime, int duration, RitualType ritual) {
        endGameTime = endTime;
        durationTicks = Math.max(0, duration);
        ritualSide = (ritual != null) ? ritual : RitualType.ANGEL;
        soundPlayed = false;
        deferResetUntilAnimEnds = false; // new run
        // If outcome was known earlier and we’re already past flashing, try to arm or backlog.
        tryPlayIfDue();
    }

    /** Back-compat overload (if server code still calls old ModNet method). */
    public static void onInfuseStarted(long endTime, int duration) {
        onInfuseStarted(endTime, duration, ritualSide /* keep last known */);
    }

    /** Called on S2C EarlyOutcome (can be sent immediately or later by the server). */
    public static void onOutcomeKnown(boolean success) {
        outcomeKnown = true;
        willSucceed = success;
        tryPlayIfDue();
    }

    /** Called on S2C InfuseResult; do NOT kill a running animation—let it finish first. */
    public static void onInfuseFinalized() {
        if (animActive) {
            deferResetUntilAnimEnds = true;
        } else {
            reset();
        }
    }

    /** Hard reset (used after animation completes or when nothing is active). */
    private static void reset() {
        endGameTime = -1L;
        durationTicks = 0;
        outcomeKnown = false;
        willSucceed = false;
        soundPlayed = false;
        animActive = false;
        animStartGameTime = 0L;
        deferResetUntilAnimEnds = false;
        // Backlog is not cleared here; it is consumed explicitly on GUI open.
    }

    // ------------------------------------------------------------------------
    // Backlog → play on GUI open
    // ------------------------------------------------------------------------

    /**
     * Called by the AngelDemonScreen when it opens (init).
     * If we backlogged the effect while the GUI was closed, play SFX and start anim now.
     */
    public static void onScreenOpenedPlayBacklog() {
        if (!pendingBacklog) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null) {
            // If somehow no world, keep backlog until we can show it.
            return;
        }

        // CLIENT CONFIG volumes
        var snap = UpgradeClientConfig.snapshot();
        double volD = pendingWillSucceed ? snap.sfxSuccessVolume : snap.sfxFailVolume;
        float volume = (float) Mth.clamp(volD, 0.0, 1.0);

        var evt = pendingWillSucceed ? ModSounds.INFUSE_SUCCESS.get() : ModSounds.INFUSE_FAIL.get();
        mc.level.playLocalSound(
                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                evt, net.minecraft.sounds.SoundSource.PLAYERS, volume, 1.0f, false
        );

        // Start the overlay animation now (use current gameTime as start)
        ClientLevel level = mc.level;
        long now = level.getGameTime();

        // Lock in the ritual/outcome from the backlog just in case newer messages arrived.
        ritualSide = pendingRitualSide;
        willSucceed = pendingWillSucceed;

        animActive = true;
        animStartGameTime = now;
        soundPlayed = true;         // we've just played it
        pendingBacklog = false;     // consumed
    }

    // ------------------------------------------------------------------------
    // Tick + scheduling
    // ------------------------------------------------------------------------

    /** Invoked every client tick (registered in client setup). */
    public static void clientTick() {
        tryPlayIfDue();
    }

    /**
     * Play exactly at flashing start (when fill phase completes).
     * If the GUI is closed at that moment, we backlog the effect and wait for GUI open.
     */
    private static void tryPlayIfDue() {
        if (!outcomeKnown) return;
        if (endGameTime < 0 || durationTicks <= 0) return;
        if (soundPlayed) return;           // already played (or backlogged and consumed)
        if (pendingBacklog) return;        // already armed to play upon next GUI open

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null) return;

        long now = mc.level.getGameTime();
        long start = endGameTime - durationTicks;

        if (now < start) return;

        // Match the same split used elsewhere (see PendingState calc on server)
        int fillTicks = ticksUntilFlashingStart(durationTicks);
        long flashingStart = start + fillTicks;

        if (now >= flashingStart) {
            Screen screen = mc.screen;
            if (screen instanceof AngelDemonScreen) {
                // CLIENT CONFIG volumes
                var snap = UpgradeClientConfig.snapshot();
                double volD = willSucceed ? snap.sfxSuccessVolume : snap.sfxFailVolume;
                float volume = (float) Mth.clamp(volD, 0.0, 1.0);

                var evt = willSucceed ? ModSounds.INFUSE_SUCCESS.get() : ModSounds.INFUSE_FAIL.get();
                mc.level.playLocalSound(
                        mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                        evt, SoundSource.PLAYERS, volume, 1.0f, false
                );
                soundPlayed = true;

                // Start the overlay animation now
                animActive = true;
                animStartGameTime = now;
            } else {
                // GUI is closed → backlog and wait for the next GUI open to play.
                pendingBacklog = true;
                pendingWillSucceed = willSucceed;
                pendingRitualSide = ritualSide;

                // Mark as "played" so we don't keep re-arming every tick; actual playback
                // (SFX + anim start) will happen in onScreenOpenedPlayBacklog().
                soundPlayed = true;
            }
        }
    }

    /**
     * Matches the fill->flash split used on server resume (see ModNet PendingStateS2C computation):
     * finale = clamp(round(duration * 0.15), 10..80); fill = duration - finale.
     */
    private static int ticksUntilFlashingStart(int duration) {
        int finale = Mth.clamp(Mth.floor(duration * 0.15f + 0.5f), 10, 80);
        int fill = duration - finale;
        return Math.max(0, fill);
    }

    // ------------------------------------------------------------------------
    // Rendering (hooked from ClientSetup via RenderGuiEvent.Post and ScreenEvent.Render.Post)
    // ------------------------------------------------------------------------

    /** Render the animation frame if active. Call from render post events. */
    public static void onRenderGuiPost(GuiGraphics gg) {
        if (!animActive) {
            // If animation ended and we were deferring reset, finalize now.
            if (deferResetUntilAnimEnds) reset();
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = (mc != null) ? mc.level : null;
        if (level == null) {
            animActive = false; // world changed
            if (deferResetUntilAnimEnds) reset();
            return;
        }

        long now = level.getGameTime();
        long elapsed = now - animStartGameTime;
        if (elapsed < 0) elapsed = 0;

        int frame = (int)(elapsed / TICKS_PER_FRAME);
        if (frame >= FRAMES) {
            animActive = false; // finished
            if (deferResetUntilAnimEnds) reset();
            return;
        }

        // Compute group position: from screen if open, else center on current GUI size
        int groupLeft;
        int groupTop;

        Screen screen = mc.screen;
        if (screen instanceof AngelDemonScreen ads) {
            groupLeft = ads.getLeftPos();
            groupTop  = ads.getTopPos();
        } else {
            // Center as-if the group (305x222) were drawn
            int w = mc.getWindow().getGuiScaledWidth();
            int h = mc.getWindow().getGuiScaledHeight();
            groupLeft = (w - GROUP_W) / 2;
            groupTop  = (h - GROUP_H) / 2;
        }

        int x = groupLeft + REL_X;
        int y = groupTop  + REL_Y;

        int srcX = frame * FRAME_W;
        int srcY = 0;

        // Pick the sheet by ritual + outcome
        String side = (ritualSide == RitualType.DEMON) ? "demon" : "angel";
        String outcome = willSucceed ? "success" : "fail";

        try {
            gg.blit(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                            "infiniteupgrades",
                            "textures/gui/container/infuse_anims/" + side + "_" + outcome + ".png"
                    ),
                    x, y,
                    srcX, srcY,
                    FRAME_W, FRAME_H,
                    SHEET_W, SHEET_H
            );
        } catch (Throwable t) {
            LOG.warn("[InfuseClientEffects] Render failed", t);
            animActive = false;
            if (deferResetUntilAnimEnds) reset();
        }
    }
}
