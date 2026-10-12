package io.github.kiyo.mediapanelhologram.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * One screen connected to a remote. pos is the SESSION KEY block
 * (MediaSource#getSessionKey: the block itself, or a panel group's
 * representative), so every panel of one group maps to the same target.
 */
public record RemoteTarget(ResourceKey<Level> dimension, BlockPos pos, String name, boolean selected) {
    public static final Codec<RemoteTarget> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(RemoteTarget::dimension),
            BlockPos.CODEC.fieldOf("pos").forGetter(RemoteTarget::pos),
            Codec.STRING.fieldOf("name").forGetter(RemoteTarget::name),
            Codec.BOOL.fieldOf("selected").forGetter(RemoteTarget::selected)
    ).apply(i, RemoteTarget::new));

    public static final StreamCodec<ByteBuf, RemoteTarget> STREAM_CODEC = StreamCodec.composite(
            ResourceKey.streamCodec(Registries.DIMENSION), RemoteTarget::dimension,
            BlockPos.STREAM_CODEC, RemoteTarget::pos,
            ByteBufCodecs.STRING_UTF8, RemoteTarget::name,
            ByteBufCodecs.BOOL, RemoteTarget::selected,
            RemoteTarget::new);
}
