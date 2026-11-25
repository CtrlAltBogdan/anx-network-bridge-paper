package run.anx.bridge;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.UUID;

/** Bedrock players through the Floodgate API, looked up reflectively so the plugin works without Geyser/Floodgate. */
public final class Bedrock {
    private Bedrock() {}

    public static boolean floodgatePresent() { return cls("org.geysermc.floodgate.api.FloodgateApi") != null; }
    public static boolean geyserPresent() { return cls("org.geysermc.geyser.api.GeyserApi") != null || cls("org.geysermc.geyser.GeyserImpl") != null; }

    /** Number of Floodgate (Bedrock) players among the given ids, or null when Floodgate is not installed. */
    public static Integer count(Collection<UUID> ids) {
        Class<?> api = cls("org.geysermc.floodgate.api.FloodgateApi");
        if (api == null) return null;
        try {
            Object inst = api.getMethod("getInstance").invoke(null);
            Method is = api.getMethod("isFloodgatePlayer", UUID.class);
            int n = 0;
            for (UUID id : ids) if (Boolean.TRUE.equals(is.invoke(inst, id))) n++;
            return n;
        } catch (ReflectiveOperationException | RuntimeException e) { return null; }
    }

    /** Visible when the platform declares Floodgate/Geyser as an optional dependency of this plugin. */
    private static Class<?> cls(String name) {
        try { return Class.forName(name, false, Bedrock.class.getClassLoader()); } catch (Throwable e) { return null; }
    }
}
