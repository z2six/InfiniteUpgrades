// MainFile: src/main/java/org/z2six/infiniteupgrades/feature/statue/client/StatueClientScreens.java
package org.z2six.infiniteupgrades.feature.statue.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.z2six.infiniteupgrades.feature.statue.block.statue.StatueKind;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Opens the infusion GUI the same way the old entities did.
 * Uses reflection to tolerate different screen APIs (constructor vs static factories).
 * This avoids compile errors like missing openAngel/openDemon.
 */
public final class StatueClientScreens {
    private static final Logger LOG = LogUtils.getLogger();

    private static final String SCREEN_CLASS = "org.z2six.infiniteupgrades.feature.infusion.client.screen.AngelDemonScreen";

    private StatueClientScreens() {}

    public static void openFor(StatueKind kind) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null) {
                LOG.warn("[StatueClientScreens] No Minecraft/player present; aborting openFor({})", kind);
                return;
            }

            Class<?> screenCls = Class.forName(SCREEN_CLASS);

            // Try static factories first: openAngel() / openDemon()
            Method mOpenAngel = findNoArg(screenCls, "openAngel");
            Method mOpenDemon = findNoArg(screenCls, "openDemon");
            if (kind.isAngel() && mOpenAngel != null) {
                mc.setScreen((net.minecraft.client.gui.screens.Screen) mOpenAngel.invoke(null));
                LOG.debug("[StatueClientScreens] Opened screen via AngelDemonScreen.openAngel()");
                return;
            }
            if (kind.isDemon() && mOpenDemon != null) {
                mc.setScreen((net.minecraft.client.gui.screens.Screen) mOpenDemon.invoke(null));
                LOG.debug("[StatueClientScreens] Opened screen via AngelDemonScreen.openDemon()");
                return;
            }

            // Try enum Mode constructor: new Screen(Mode.ANGEL/DEMON)
            Class<?>[] inner = screenCls.getDeclaredClasses();
            Class<?> modeEnum = null;
            Object angelConst = null, demonConst = null;
            for (Class<?> c : inner) {
                if (c.isEnum() && c.getSimpleName().equalsIgnoreCase("Mode")) {
                    modeEnum = c;
                    break;
                }
            }
            if (modeEnum != null) {
                Object[] constants = modeEnum.getEnumConstants();
                for (Object o : constants) {
                    String n = o.toString();
                    if ("ANGEL".equalsIgnoreCase(n)) angelConst = o;
                    if ("DEMON".equalsIgnoreCase(n)) demonConst = o;
                }
                Object which = kind.isAngel() ? angelConst : demonConst;
                if (which != null) {
                    Constructor<?> ctor = safeFindCtor(screenCls, modeEnum);
                    if (ctor != null) {
                        mc.setScreen((net.minecraft.client.gui.screens.Screen) ctor.newInstance(which));
                        LOG.debug("[StatueClientScreens] Opened screen via AngelDemonScreen(Mode.{})", which);
                        return;
                    }
                }
            }

            // Try boolean constructor: new Screen(boolean isAngel)
            Constructor<?> boolCtor = safeFindCtor(screenCls, boolean.class);
            if (boolCtor != null) {
                mc.setScreen((net.minecraft.client.gui.screens.Screen) boolCtor.newInstance(kind.isAngel()));
                LOG.debug("[StatueClientScreens] Opened screen via AngelDemonScreen(boolean isAngel={})", kind.isAngel());
                return;
            }

            // Try no-arg constructor as a last resort
            Constructor<?> noArg = safeFindCtor(screenCls);
            if (noArg != null) {
                mc.setScreen((net.minecraft.client.gui.screens.Screen) noArg.newInstance());
                LOG.debug("[StatueClientScreens] Opened screen via AngelDemonScreen()");
                return;
            }

            LOG.error("[StatueClientScreens] Could not find a usable factory/constructor on {}", SCREEN_CLASS);
        } catch (Throwable t) {
            LOG.error("[StatueClientScreens] Failed to open screen for {}: {}", kind, t.toString());
        }
    }

    private static Method findNoArg(Class<?> cls, String name) {
        try {
            Method m = cls.getDeclaredMethod(name);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Constructor<?> safeFindCtor(Class<?> cls, Class<?>... params) {
        try {
            Constructor<?> c = cls.getDeclaredConstructor(params);
            c.setAccessible(true);
            return c;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
