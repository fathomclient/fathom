package net.fathom.optimizations.fabric.input;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.BookEditScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.SignEditScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;

import net.fathom.optimizations.core.input.HubKeyPolicy;
import net.fathom.optimizations.fabric.gfx.FathomScreen;
import net.fathom.optimizations.fabric.hub.FathomHubScreen;

/**
 * Describes the current screen in the terms {@link HubKeyPolicy} decides on.
 *
 * <h2>How "takes text" is recognised</h2>
 *
 * <p>Chat, signs and books are named, because the last two draw their own text
 * rather than using a text field. Everything else that takes text in 1.8.9 —
 * the server address, the anvil, the command block, the world name, and any
 * mod screen built the ordinary way — holds a {@link TextFieldWidget}, so a
 * screen class with one among its fields is treated as typing. The answer is
 * cached per class: it is asked every frame and the class does not change.
 */
public final class ScreenKinds {

    private static final Map<Class<?>, Boolean> TEXT_FIELDS = new IdentityHashMap<Class<?>, Boolean>();

    private ScreenKinds() {}

    public static HubKeyPolicy.Screen of(Screen screen) {
        if (screen == null) {
            return HubKeyPolicy.Screen.NONE;
        }
        if (screen instanceof FathomHubScreen) {
            return HubKeyPolicy.Screen.HUB;
        }
        if (screen instanceof FathomScreen) {
            return HubKeyPolicy.Screen.OTHER_FATHOM;
        }
        if (screen instanceof HandledScreen) {
            // Before the text check: the anvil is both, and either answer
            // means the key must leave it alone.
            return HubKeyPolicy.Screen.CONTAINER;
        }
        if (screen instanceof ChatScreen || screen instanceof SignEditScreen || screen instanceof BookEditScreen
                || holdsTextField(screen.getClass())) {
            return HubKeyPolicy.Screen.TEXT_INPUT;
        }
        return HubKeyPolicy.Screen.MENU;
    }

    private static boolean holdsTextField(Class<?> type) {
        Boolean known = TEXT_FIELDS.get(type);
        if (known != null) {
            return known.booleanValue();
        }
        boolean found = false;
        try {
            for (Class<?> each = type; each != null && each != Screen.class && !found; each = each.getSuperclass()) {
                for (Field field : each.getDeclaredFields()) {
                    if (TextFieldWidget.class.isAssignableFrom(field.getType())) {
                        found = true;
                        break;
                    }
                }
            }
        } catch (RuntimeException | LinkageError unreadable) {
            // A class whose fields cannot be listed is assumed to be typing:
            // swallowing a capital letter is harmless, opening over it is not.
            found = true;
        }
        TEXT_FIELDS.put(type, Boolean.valueOf(found));
        return found;
    }
}
