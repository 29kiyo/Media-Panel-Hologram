package io.github.kiyo.mediapanelhologram.item;

import net.minecraft.world.InteractionHand;
import org.jetbrains.annotations.Nullable;

/** Static access point; set once from each platform's client entrypoint. No-op on a dedicated server. */
public final class RemoteOpenerProvider {
    @Nullable
    private static RemoteOpener instance;

    public static void setInstance(RemoteOpener opener) {
        instance = opener;
    }

    public static void open(InteractionHand hand) {
        if (instance != null) {
            instance.open(hand);
        }
    }

    private RemoteOpenerProvider() {
    }
}
