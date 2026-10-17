package net.fathom.optimizations.fabric;

import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric's way in. The client itself is loader-neutral, so it can also start
 * under Forge, where this class is never loaded: the interface it implements
 * only exists on Fabric.
 */
public final class FabricEntrypoint implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        new FathomOptimizationsClient().onInitializeClient();
    }
}
