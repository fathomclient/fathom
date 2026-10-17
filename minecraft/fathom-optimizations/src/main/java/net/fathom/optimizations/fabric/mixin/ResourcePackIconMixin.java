package net.fathom.optimizations.fabric.mixin;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.fathom.optimizations.core.opt.resource.PackIconPolicy;
import net.fathom.optimizations.fabric.Runtime;

/**
 * Resource pack icons, downscaled on load -- {@code resources.pack-icon-downscale}.
 *
 * <p>Vanilla reads a pack's {@code pack.png} into {@code image} in
 * {@code loadIcon} and uploads it as-is in {@code bindIcon}, for the session,
 * at whatever size its author exported it. The resource pack screen draws it at
 * 32 pixels. {@link PackIconPolicy} decides the size (64 on the longest edge,
 * never larger than the source); this replaces the image between the two
 * calls, so what is uploaded is already small. Once per pack, on the screen
 * that lists them -- never on a frame path.
 *
 * <p>The scaling is two-pass bilinear (halving, then the last step), which is
 * what an area filter would produce at these ratios and keeps the icon from
 * aliasing the way a single nearest-neighbour jump from 512 to 64 would.
 */
@Mixin(targets = "net.minecraft.client.resource.ResourcePackLoader$Entry")
public abstract class ResourcePackIconMixin {

    @Shadow private BufferedImage image;

    @Inject(method = "loadIcon()V", at = @At("RETURN"))
    private void fathom$downscaleIcon(CallbackInfo info) {
        BufferedImage source = image;
        if (source == null || Runtime.switches() == null
                || !Runtime.switches().isEnabled(Runtime.PACK_ICON_DOWNSCALE)) {
            return;
        }
        PackIconPolicy.Plan plan = PackIconPolicy.plan(source.getWidth(), source.getHeight());
        if (!plan.changed()) {
            return;
        }
        image = fathom$scale(source, plan.targetWidth(), plan.targetHeight());
        // Once per pack, when its icon loads: the only evidence a player has
        // that this ran, and it costs one line for each oversized icon.
        System.out.println("[Fathom] Pack icon " + plan + ".");
    }

    @Unique
    private static BufferedImage fathom$scale(BufferedImage source, int width, int height) {
        BufferedImage current = source;
        int w = source.getWidth();
        int h = source.getHeight();
        while (w > width || h > height) {
            w = Math.max(width, w / 2);
            h = Math.max(height, h / 2);
            BufferedImage next = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = next.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(current, 0, 0, w, h, null);
            } finally {
                graphics.dispose();
            }
            current = next;
        }
        return current;
    }
}
