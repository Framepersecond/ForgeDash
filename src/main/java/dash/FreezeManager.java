package dash;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static utility class that manages frozen players.
 * In NeoForge, call {@link #handleTick(MinecraftServer)} from the server tick event to enforce freeze positions.
 * Call {@link #onDisconnect(UUID)} from a disconnect callback to clean up.
 */
public class FreezeManager {

    private static final Set<UUID> frozenPlayers = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Vec3> lastPositions = new ConcurrentHashMap<>();

    public static Set<UUID> getFrozenPlayers() {
        return frozenPlayers;
    }

    public static boolean isFrozen(UUID uuid) {
        return frozenPlayers.contains(uuid);
    }

    public static void freeze(UUID uuid) {
        frozenPlayers.add(uuid);
        lastPositions.remove(uuid);
    }

    public static void unfreeze(UUID uuid) {
        frozenPlayers.remove(uuid);
        lastPositions.remove(uuid);
    }

    public static boolean toggleFreeze(UUID uuid) {
        if (frozenPlayers.contains(uuid)) {
            unfreeze(uuid);
            return false;
        } else {
            freeze(uuid);
            return true;
        }
    }

    /**
     * Called each server tick. Teleports frozen players back to their
     * last known position if they moved.
     */
    public static void handleTick(MinecraftServer server) {
        if (frozenPlayers.isEmpty()) return;

        for (UUID uuid : frozenPlayers) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) continue;

            Vec3 current = new Vec3(player.getX(), player.getY(), player.getZ());
            Vec3 locked = lastPositions.get(uuid);

            if (locked == null) {
                lastPositions.put(uuid, current);
                player.setDeltaMovement(0.0, 0.0, 0.0);
                continue;
            }

            if (current.x != locked.x || current.y != locked.y || current.z != locked.z) {
                player.teleportTo(
                        (net.minecraft.server.level.ServerLevel) player.level(),
                        locked.x, locked.y, locked.z,
                        java.util.Set.of(),
                        player.getYRot(), player.getXRot(),
                        true
                );
            }
            player.setDeltaMovement(0.0, 0.0, 0.0);
        }
    }

    /** Clean up when a player disconnects. */
    public static void onDisconnect(UUID uuid) {
        frozenPlayers.remove(uuid);
        lastPositions.remove(uuid);
    }
}
