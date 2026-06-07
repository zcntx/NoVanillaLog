package cn.nskc.spawn.novanillalog;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Netty-based packet filter that intercepts outgoing
 * {@code ClientboundSystemChatPacket} to suppress server→client system
 * messages (e.g. "Applied effect Resistance to …") that bypass
 * {@code AsyncChatEvent}.
 *
 * <p>Uses Paper's {@code ChannelInitializeListenerHolder} (via reflection)
 * for new connections and direct Netty channel injection for
 * already-connected players.  No ProtocolLib or NMS compile dependency
 * required.</p>
 */
public class PacketFilter {

    private static final String HANDLER_NAME = "novanillalog_chat_filter";
    private static final String PACKET_CLASS =
            "net.minecraft.network.protocol.game.ClientboundSystemChatPacket";

    private final Plugin plugin;
    private final List<String> patterns;
    private Object listenerKey; // net.kyori.adventure.key.Key

    public PacketFilter(Plugin plugin, List<String> patterns) {
        this.plugin = plugin;
        this.patterns = new ArrayList<>(patterns);
    }

    /**
     * Register the Netty channel listener and inject into existing players.
     */
    public void register() {
        try {
            // Key.key("novanillalog", "chat_filter")
            Class<?> keyClass = Class.forName("net.kyori.adventure.key.Key");
            Method keyMethod = keyClass.getMethod("key", String.class, String.class);
            listenerKey = keyMethod.invoke(null, "novanillalog", "chat_filter");

            // ChannelInitializeListenerHolder.addListener(key, channel -> injectHandler(channel))
            Class<?> holderClass = Class.forName("io.papermc.paper.network.ChannelInitializeListenerHolder");
            Class<?> listenerClass = Class.forName("io.papermc.paper.network.ChannelInitializeListener");

            // Create a proxy listener
            Object listener = java.lang.reflect.Proxy.newProxyInstance(
                    plugin.getClass().getClassLoader(),
                    new Class<?>[]{listenerClass},
                    (proxy, method, args) -> {
                        if ("afterInitChannel".equals(method.getName())) {
                            injectHandler((Channel) args[0]);
                        }
                        return null;
                    }
            );

            Method addListener = holderClass.getMethod("addListener", keyClass, listenerClass);
            addListener.invoke(null, listenerKey, listener);

            plugin.getLogger().info("Channel listener registered for new connections.");

            // Inject into already-connected players
            for (Player player : Bukkit.getOnlinePlayers()) {
                injectPlayer(player);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to register channel listener: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Unregister the Netty channel listener and remove handlers from all players.
     */
    public void unregister() {
        // Remove handler from all online players' channels
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                Channel channel = getChannel(player);
                if (channel != null) {
                    channel.eventLoop().execute(() -> {
                        if (channel.pipeline().get(HANDLER_NAME) != null) {
                            channel.pipeline().remove(HANDLER_NAME);
                        }
                    });
                }
            } catch (Exception ignored) {
            }
        }

        // Remove the channel initializer listener
        try {
            if (listenerKey != null) {
                Class<?> holderClass = Class.forName("io.papermc.paper.network.ChannelInitializeListenerHolder");
                Class<?> keyClass = Class.forName("net.kyori.adventure.key.Key");
                Method removeListener = holderClass.getMethod("removeListener", keyClass);
                removeListener.invoke(null, listenerKey);
                listenerKey = null;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to unregister channel listener: " + e.getMessage());
        }
    }

    /**
     * Inject the handler into an existing player's channel.
     */
    public void injectPlayer(Player player) {
        try {
            Channel channel = getChannel(player);
            if (channel != null) {
                channel.eventLoop().execute(() -> {
                    if (channel.pipeline().get(HANDLER_NAME) == null) {
                        injectHandler(channel);
                    }
                });
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to inject handler for " + player.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Add the outbound handler to the channel pipeline.
     */
    private void injectHandler(Channel channel) {
        try {
            if (channel.pipeline().get(HANDLER_NAME) == null) {
                channel.pipeline().addBefore("packet_handler", HANDLER_NAME,
                        new ChatFilterHandler());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to add handler to channel: " + e.getMessage());
        }
    }

    /**
     * Get the Netty channel from a player via NMS reflection.
     */
    private Channel getChannel(Player player) throws Exception {
        // CraftPlayer.getHandle() -> ServerPlayer
        Object craftPlayer = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer").cast(player);
        Object serverPlayer = craftPlayer.getClass().getMethod("getHandle").invoke(craftPlayer);

        // ServerPlayer.connection -> ServerGamePacketListenerImpl
        Field connectionField = findField(serverPlayer.getClass(), "connection");
        Object packetListener = connectionField.get(serverPlayer);

        // ServerGamePacketListenerImpl.connection -> NMS Connection
        Field nmsConnectionField = findField(packetListener.getClass(), "connection");
        Object nmsConnection = nmsConnectionField.get(packetListener);

        // NMS Connection.channel -> Netty Channel
        Field channelField = findField(nmsConnection.getClass(), "channel");
        return (Channel) channelField.get(nmsConnection);
    }

    /**
     * Find a field by name, searching up the class hierarchy.
     */
    private Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field '" + name + "' not found in " + clazz.getName());
    }

    /**
     * Replace the filter patterns at runtime (for /novanillalog reload).
     */
    public void updatePatterns(List<String> newPatterns) {
        patterns.clear();
        patterns.addAll(newPatterns);
    }

    /**
     * Return a defensive copy of current patterns.
     */
    public List<String> getPatterns() {
        return new ArrayList<>(patterns);
    }

    /**
     * Netty outbound handler that inspects and optionally cancels
     * {@code ClientboundSystemChatPacket} messages.
     */
    private class ChatFilterHandler extends ChannelOutboundHandlerAdapter {

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg != null && msg.getClass().getName().equals(PACKET_CLASS)) {
                String text = extractText(msg);
                if (text != null && matchesPattern(text)) {
                    promise.setSuccess();
                    return;
                }
            }
            super.write(ctx, msg, promise);
        }

        /**
         * Extract the text content from the packet's Component field.
         * Tries Adventure Component serialization first, then falls back
         * to toString() which contains translation keys for NMS Components.
         */
        private String extractText(Object packet) {
            try {
                Field[] fields = packet.getClass().getDeclaredFields();
                if (fields.length == 0) return null;

                fields[0].setAccessible(true);
                Object component = fields[0].get(packet);
                if (component == null) return null;

                // Case 1: Adventure Component wrapper (Paper)
                //   AdventureComponent implements Adventure's Component interface
                //   Use PlainTextComponentSerializer to get the rendered text
                try {
                    Class<?> advCompClass = Class.forName("net.kyori.adventure.text.Component");
                    if (advCompClass.isInstance(component)) {
                        Object advComponent = advCompClass.cast(component);
                        Class<?> plainSerializer = Class.forName(
                                "net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer");
                        Object serializer = plainSerializer.getMethod("plainText").invoke(null);
                        String text = (String) plainSerializer.getMethod("serialize", advCompClass)
                                .invoke(serializer, advComponent);
                        return text;
                    }
                } catch (Exception ignored) {
                }

                // Case 2: NMS Component (MutableComponent)
                //   toString() contains translation keys like
                //   translation{key='commands.damage.success', ...}
                //   Use this for matching — translation keys are language-independent
                return component.toString();

            } catch (Exception e) {
                return null;
            }
        }

        /**
         * Check if the text matches any configured pattern (case-insensitive).
         */
        private boolean matchesPattern(String text) {
            String lower = text.toLowerCase();
            for (String pattern : patterns) {
                if (!pattern.isEmpty() && lower.contains(pattern.toLowerCase())) {
                    return true;
                }
            }
            return false;
        }
    }
}
