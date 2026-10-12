package io.github.kiyo.mediapanelhologram.item;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Data component value stored on a remote ItemStack: its connected screens (immutable). */
public record RemoteData(List<RemoteTarget> targets) {
    public static final int MAX_TARGETS = 16;
    public static final RemoteData EMPTY = new RemoteData(List.of());

    public static final Codec<RemoteData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.list(RemoteTarget.CODEC, 0, MAX_TARGETS).fieldOf("targets").forGetter(RemoteData::targets)
    ).apply(i, RemoteData::new));

    public static final StreamCodec<ByteBuf, RemoteData> STREAM_CODEC = StreamCodec.composite(
            RemoteTarget.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_TARGETS)), RemoteData::targets,
            RemoteData::new);

    public RemoteData {
        targets = List.copyOf(targets);
    }

    public int indexOf(ResourceKey<Level> dimension, BlockPos pos) {
        for (int i = 0; i < targets.size(); i++) {
            RemoteTarget t = targets.get(i);
            if (t.dimension().equals(dimension) && t.pos().equals(pos)) {
                return i;
            }
        }
        return -1;
    }

    /** Smallest "Media N" (N >= 1) not used by any target yet. */
    public String nextName() {
        for (int n = 1; ; n++) {
            String candidate = "Media " + n;
            boolean used = false;
            for (RemoteTarget t : targets) {
                if (t.name().equals(candidate)) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                return candidate;
            }
        }
    }

    public RemoteData with(RemoteTarget target) {
        List<RemoteTarget> list = new ArrayList<>(targets);
        list.add(target);
        return new RemoteData(list);
    }

    /**
     * Server-side check against the world. In the given level's dimension, for chunks that are
     * loaded: a target whose block is no longer a MediaSource is dropped; a target whose
     * session key moved (panel group merged/split) is re-pointed at the new key. Targets in
     * other dimensions or unloaded chunks are kept untouched (state unknown). Duplicates
     * created by re-pointing are merged. Returns this when nothing changed.
     */
    public RemoteData validated(Level level) {
        List<RemoteTarget> out = new ArrayList<>();
        boolean changed = false;
        for (RemoteTarget t : targets) {
            RemoteTarget keep = t;
            if (t.dimension().equals(level.dimension()) && level.isLoaded(t.pos())) {
                if (!(level.getBlockEntity(t.pos()) instanceof io.github.kiyo.mediapanelhologram.block.entity.MediaSource src)) {
                    changed = true;
                    continue;
                }
                BlockPos key = src.getSessionKey();
                if (!key.equals(t.pos())) {
                    keep = new RemoteTarget(t.dimension(), key, t.name(), t.selected());
                    changed = true;
                }
            }
            boolean dup = false;
            for (RemoteTarget o : out) {
                if (o.dimension().equals(keep.dimension()) && o.pos().equals(keep.pos())) {
                    dup = true;
                    break;
                }
            }
            if (dup) {
                changed = true;
            } else {
                out.add(keep);
            }
        }
        return changed ? new RemoteData(out) : this;
    }

    public RemoteData replace(int index, RemoteTarget target) {
        List<RemoteTarget> list = new ArrayList<>(targets);
        list.set(index, target);
        return new RemoteData(list);
    }

    public RemoteData without(int index) {
        List<RemoteTarget> list = new ArrayList<>(targets);
        list.remove(index);
        return new RemoteData(list);
    }
}
