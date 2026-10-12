package io.github.kiyo.mediapanelhologram.network;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Playback sync never sends page URLs: clients send (and the server compares) this 64-bit hash of the
 * page URL (fragment already removed). 0 means "no URL". This keeps URLs the player did not share
 * (sign-in pages, ...) off the network and out of server memory and logs. It is not secret against
 * someone who can guess the URL, only against anyone who cannot.
 */
public final class PlaybackUrl {
    private PlaybackUrl() {
    }

    public static long hash(String url) {
        if (url == null || url.isEmpty()) {
            return 0L;
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            long h = 0L;
            for (int i = 0; i < 8; i++) {
                h = (h << 8) | (d[i] & 0xFFL);
            }
            return h == 0L ? 1L : h;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
