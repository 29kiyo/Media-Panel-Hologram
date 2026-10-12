package io.github.kiyo.mediapanelhologram.block;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Operation lock of one screen (Media Block / Hologram Block / panel group).
 * Owner and allowed players are stored by UUID; names are for display only.
 * NONE = unlocked. Only OPERATION permission is managed here (breaking is left
 * to vanilla / protection mods, see CLAUDE.md 5).
 */
public record LockData(boolean locked, UUID owner, String ownerName, List<Entry> allowed) {
    public static final int MAX_ALLOWED = 32;
    public static final LockData NONE = new LockData(false, new UUID(0L, 0L), "", List.of());

    public record Entry(UUID id, String name) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("id").forGetter(Entry::id),
                Codec.STRING.fieldOf("name").forGetter(Entry::name)).apply(i, Entry::new));
        public static final StreamCodec<ByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Entry::id,
                ByteBufCodecs.STRING_UTF8, Entry::name,
                Entry::new);
    }

    public static final StreamCodec<ByteBuf, LockData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, LockData::locked,
            UUIDUtil.STREAM_CODEC, LockData::owner,
            ByteBufCodecs.STRING_UTF8, LockData::ownerName,
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ALLOWED)), LockData::allowed,
            LockData::new);

    public static LockData lockedBy(UUID owner, String ownerName) {
        return new LockData(true, owner, ownerName, List.of());
    }

    public boolean isOwner(UUID id) {
        return locked && owner.equals(id);
    }

    public boolean isAllowed(UUID id) {
        if (!locked) {
            return false;
        }
        for (Entry e : allowed) {
            if (e.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** Adds (or renames) an allowed player. Returns this unchanged when full or not locked. */
    public LockData withAllowed(UUID id, String name) {
        if (!locked) {
            return this;
        }
        List<Entry> next = new ArrayList<>();
        boolean found = false;
        for (Entry e : allowed) {
            if (e.id().equals(id)) {
                next.add(new Entry(id, name));
                found = true;
            } else {
                next.add(e);
            }
        }
        if (!found) {
            if (next.size() >= MAX_ALLOWED) {
                return this;
            }
            next.add(new Entry(id, name));
        }
        return new LockData(true, owner, ownerName, List.copyOf(next));
    }

    public LockData withoutAllowed(UUID id) {
        List<Entry> next = new ArrayList<>();
        for (Entry e : allowed) {
            if (!e.id().equals(id)) {
                next.add(e);
            }
        }
        return new LockData(locked, owner, ownerName, List.copyOf(next));
    }

    /** Writes nothing when unlocked (absent keys load as NONE). */
    public void save(ValueOutput output) {
        if (!locked) {
            return;
        }
        output.putBoolean("Locked", true);
        output.store("LockOwner", UUIDUtil.CODEC, owner);
        output.putString("LockOwnerName", ownerName);
        output.store("LockAllowed", Entry.CODEC.listOf(), allowed);
    }

    public static LockData load(ValueInput input) {
        if (!input.getBooleanOr("Locked", false)) {
            return NONE;
        }
        UUID owner = input.read("LockOwner", UUIDUtil.CODEC).orElse(null);
        if (owner == null) {
            return NONE;
        }
        String name = input.getStringOr("LockOwnerName", "");
        List<Entry> allowed = input.read("LockAllowed", Entry.CODEC.listOf()).orElse(List.of());
        if (allowed.size() > MAX_ALLOWED) {
            allowed = allowed.subList(0, MAX_ALLOWED);
        }
        return new LockData(true, owner, name, List.copyOf(allowed));
    }
}
