package gg.nano.ui;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
// 26.2 renamed ResourceLocation to Identifier.
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;

/**
 * Raw text carried on the {@code nanocore:ui} channel.
 *
 * <p>The codec reads and writes the whole buffer as UTF-8 with no length prefix, because the
 * other end is a Bukkit plugin using {@code sendPluginMessage}, which frames nothing. Using
 * {@code writeUtf} here would add a VarInt the server never wrote.
 */
public record UiPayload(String data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<UiPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("nanocore", "ui"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UiPayload> CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeBytes(payload.data().getBytes(StandardCharsets.UTF_8)),
            buf -> {
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                return new UiPayload(new String(bytes, StandardCharsets.UTF_8));
            });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
