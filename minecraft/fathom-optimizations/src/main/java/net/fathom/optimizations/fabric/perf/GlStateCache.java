package net.fathom.optimizations.fabric.perf;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import com.mojang.blaze3d.platform.GlStateManager;

import net.fathom.optimizations.fabric.mixin.GlStateManagerAccessor;

/**
 * Read the values Minecraft already tracks, without synchronizing with the GL
 * driver. This is suitable for a hot restore path; diagnostic truth still
 * comes from {@code GlStateAudit}'s sparse raw GL queries.
 */
final class GlStateCache {
    private GlStateCache() {}

    private static final Field TEXTURES = findTextureArray();
    private static final Field BOUND_TEXTURE = findBoundTexture(TEXTURES);

    static boolean available() {
        return TEXTURES != null && BOUND_TEXTURE != null;
    }

    static int boundTexture() {
        if (!available()) return -1;
        int unit = GlStateManagerAccessor.fathom$getActiveTexture();
        try {
            Object textures = TEXTURES.get(null);
            if (unit < 0 || unit >= Array.getLength(textures)) return -1;
            Object state = Array.get(textures, unit);
            return state == null ? -1 : BOUND_TEXTURE.getInt(state);
        } catch (Throwable unavailable) {
            return -1;
        }
    }

    /**
     * The field names are remapped between development and the live client, so
     * reflection by source name would be brittle. The texture cache is the one
     * static state array with enough entries for all texture units whose entry
     * carries exactly one integer (the bound texture id). Resolve that shape
     * once; hot calls only perform two cached Field reads.
     */
    private static Field findTextureArray() {
        try {
            for (Field field : GlStateManager.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || !field.getType().isArray()) continue;
                field.setAccessible(true);
                Object value = field.get(null);
                if (value != null && Array.getLength(value) >= 16
                        && integerField(field.getType().getComponentType()) != null) {
                    return field;
                }
            }
        } catch (Throwable ignored) {
            // The caller falls back to vanilla when this cache shape changes.
        }
        return null;
    }

    private static Field findBoundTexture(Field textures) {
        if (textures == null) return null;
        Field field = integerField(textures.getType().getComponentType());
        if (field != null) field.setAccessible(true);
        return field;
    }

    private static Field integerField(Class<?> type) {
        Field found = null;
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && field.getType() == Integer.TYPE) {
                if (found != null) return null;
                found = field;
            }
        }
        return found;
    }
}
