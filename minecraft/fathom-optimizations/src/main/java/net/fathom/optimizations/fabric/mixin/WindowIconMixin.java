package net.fathom.optimizations.fabric.mixin;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.MinecraftClient;

/**
 * The game window carries Fathom's app icon, the same one as the launcher and
 * the installer, so the taskbar shows one product from launch to play.
 *
 * <p>Vanilla's {@code setDefaultIcon} reads its 16 and 32 px icons from the
 * default resource pack and hands them to LWJGL. This hands LWJGL ours
 * instead: the 16 and 32 px rasters {@code assets/brand/build-brand.mjs} drew
 * for exactly those sizes, not one image scaled down. If either cannot be
 * read, vanilla's own icon is set, as it always was.
 */
@Mixin(MinecraftClient.class)
public abstract class WindowIconMixin {

    @Inject(method = "setDefaultIcon", at = @At("HEAD"), cancellable = true)
    private void fathom$setIcon(CallbackInfo ci) {
        ByteBuffer small = fathom$icon(16);
        ByteBuffer large = fathom$icon(32);
        if (small != null && large != null) {
            Display.setIcon(new ByteBuffer[] {small, large});
            ci.cancel();
        }
    }

    /** One icon size as the RGBA bytes LWJGL takes, or null if it cannot be read. */
    private static ByteBuffer fathom$icon(int size) {
        String name = "/assets/fathom-optimizations/icon/fathom-" + size + ".png";
        try (InputStream in = WindowIconMixin.class.getResourceAsStream(name)) {
            if (in == null) {
                return null;
            }
            BufferedImage image = ImageIO.read(in);
            if (image == null || image.getWidth() != size || image.getHeight() != size) {
                return null;
            }
            ByteBuffer pixels = BufferUtils.createByteBuffer(size * size * 4);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int argb = image.getRGB(x, y);
                    pixels.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
                }
            }
            pixels.flip();
            return pixels;
        } catch (Exception e) {
            return null;
        }
    }
}
