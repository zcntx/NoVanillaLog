package cn.nskc.spawn.novanillalog;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.bukkit.NamespacedKey;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Installs a Netty outbound handler on every player channel to drop
 * {@code ClientboundSystemChatPacket}s whose text matches spam patterns.
 *
 * All Paper / Minecraft internal API access is done via reflection +
 * {@code MethodHandle} so the plugin compiles against the public Paper API
 * alone.  At runtime (on a real Paper server) the internal classes are
 * present and the calls resolve normally.
 */
public class PacketFilter {

    private static final String PACKET_CLASS =
            "net.minecraft.network.protocol.game.ClientboundSystemChatPacket";
    private static final String LISTENER_HOLDER_CLASS =
            "io.papermc.paper.network.ChannelInitializeListenerHolder";

    private final List<String> patterns;
    private final ChannelDuplexHandler handler;
    private final Logger logger;

    // ── Reflective handles (resolved once at construction) ─────────────────

    private final MethodHandle addListenerHandle;
    private final MethodHandle removeListenerHandle;
    private final MethodHandle componentContent;   // ClientboundSystemChatPacket.content()
    private final MethodHandle componentGetString; // Component.getString()

    public PacketFilter(List<String> patterns, Logger logger) {
        this.patterns = new ArrayList<>(patterns);
        this.logger = logger;
        this.handler = createHandler();

        MethodHandles.Lookup lookup = MethodHandles.lookup();
        addListenerHandle     = resolveListenerMethod(lookup, "addListener");
        removeListenerHandle  = resolveListenerMethod(lookup, "removeListener");
        componentContent      = resolvePacketMethod(lookup);
        componentGetString    = resolveComponentMethod(lookup);
    }

    // ── Netty handler ──────────────────────────────────────────────────────

    private ChannelDuplexHandler createHandler() {
        return new ChannelDuplexHandler() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                if (componentContent != null && componentGetString != null) {
                    if (msg.getClass().getName().equals(PACKET_CLASS)) {
                        try {
                            Object component = componentContent.invoke(msg);
                            if (component != null) {
                                String text = (String) componentGetString.invoke(component);
                                if (matches(text)) {
                                    return; // drop the packet
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
                super.write(ctx, msg, promise);
            }
        };
    }

    // ── Register / unregister with Paper ───────────────────────────────────

    public void register(NamespacedKey key) {
        if (addListenerHandle == null) {
            logger.warning("PacketFilter: ChannelInitializeListenerHolder not available "
                    + "— in-game suppression disabled.");
            return;
        }
        try {
            // Create a dynamic proxy that implements the REAL
            // io.papermc.paper.network.ChannelInitializeListener interface
            // at runtime.  A lambda / functional-interface cast won't work
            // because the JVM sees our private interface as a different type.
            Class<?> listenerClass = Class.forName(
                    "io.papermc.paper.network.ChannelInitializeListener");
            Object listener = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    (proxy, method, args) -> {
                        if (method.getName().equals("initializeChannel") && args.length == 1) {
                            installHandler((Channel) args[0]);
                            return null;
                        }
                        return null;
                    });
            addListenerHandle.invoke(key, listener);
            logger.info("PacketFilter registered for in-game suppression.");
        } catch (Throwable e) {
            logger.log(Level.WARNING, "PacketFilter: failed to register listener", e);
        }
    }

    public void unregister(NamespacedKey key) {
        if (removeListenerHandle == null) return;
        try {
            removeListenerHandle.invoke(key);
        } catch (Throwable ignored) {
        }
    }

    private void installHandler(Channel channel) {
        channel.pipeline().addBefore("packet_handler", "novanillalog_packet_filter", handler);
    }

    // ── Pattern matching ───────────────────────────────────────────────────

    private boolean matches(String text) {
        if (text == null || text.isEmpty() || patterns.isEmpty()) return false;
        for (String p : patterns) {
            if (!p.isEmpty() && text.contains(p)) {
                return true;
            }
        }
        return false;
    }

    // ── Runtime updates ────────────────────────────────────────────────────

    public void updatePatterns(List<String> newPatterns) {
        patterns.clear();
        patterns.addAll(newPatterns);
    }

    // ── Reflective method resolution ───────────────────────────────────────

    private static MethodHandle resolveListenerMethod(MethodHandles.Lookup lookup, String name) {
        try {
            Class<?> holder = Class.forName(LISTENER_HOLDER_CLASS);
            // void addListener(NamespacedKey, ChannelInitializeListener)
            // void removeListener(NamespacedKey)
            for (Method m : holder.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() >= 1) {
                    return lookup.unreflect(m);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static MethodHandle resolvePacketMethod(MethodHandles.Lookup lookup) {
        try {
            Class<?> packet = Class.forName(PACKET_CLASS);
            Method m = packet.getDeclaredMethod("content");
            return lookup.unreflect(m);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static MethodHandle resolveComponentMethod(MethodHandles.Lookup lookup) {
        try {
            Class<?> component = Class.forName("net.minecraft.network.chat.Component");
            Method m = component.getDeclaredMethod("getString");
            return lookup.unreflect(m);
        } catch (Throwable ignored) {
        }
        return null;
    }
}
