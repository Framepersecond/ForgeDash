package dash;

import dash.data.GuardianDataManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GuardianForgeEvents {
    private static final Map<String, Snapshot> OPEN_CONTAINERS = new ConcurrentHashMap<>();

    private GuardianForgeEvents() {
    }

    public static void onBlockBreak(BreakBlockEvent event) {
        if (!FeatureFlags.enabled("guardian")) return;
        if (event.isCanceled() || !(event.getPlayer() instanceof ServerPlayer player)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        GuardianDataManager manager = FabricDash.getGuardianDataManager();
        if (manager == null) {
            return;
        }
        var pos = event.getPos();
        String block = blockName(event.getState());
        manager.logBlockActionAsync(
                player.getUUID().toString(),
                player.getName().getString(),
                GuardianDataManager.ACTION_BREAK,
                worldName(level),
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                block,
                block,
                "dash");
    }

    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!FeatureFlags.enabled("guardian")) return;
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        GuardianDataManager manager = FabricDash.getGuardianDataManager();
        if (manager == null) {
            return;
        }
        var pos = event.getPos();
        manager.logBlockActionAsync(
                player.getUUID().toString(),
                player.getName().getString(),
                GuardianDataManager.ACTION_PLACE,
                worldName(level),
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                blockName(event.getPlacedBlock()),
                blockName(event.getPlacedAgainst()),
                "dash");
    }

    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!FeatureFlags.enabled("guardian")) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Snapshot snapshot = snapshot(event.getContainer());
        if (snapshot.target != null) {
            OPEN_CONTAINERS.put(key(player.getUUID(), event.getContainer()), snapshot);
        }
    }

    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (!FeatureFlags.enabled("guardian")) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Snapshot before = OPEN_CONTAINERS.remove(key(player.getUUID(), event.getContainer()));
        if (before == null || before.target == null) {
            return;
        }
        GuardianDataManager manager = FabricDash.getGuardianDataManager();
        if (manager == null) {
            return;
        }
        Snapshot after = snapshot(event.getContainer());
        Map<String, Integer> merged = new HashMap<>(before.counts);
        after.counts.forEach((material, count) -> merged.putIfAbsent(material, 0));
        for (String material : merged.keySet()) {
            int beforeCount = before.counts.getOrDefault(material, 0);
            int afterCount = after.counts.getOrDefault(material, 0);
            int delta = afterCount - beforeCount;
            if (delta > 0) {
                log(manager, player, before.target, GuardianDataManager.CONTAINER_ACTION_ADD, material, delta);
            } else if (delta < 0) {
                log(manager, player, before.target, GuardianDataManager.CONTAINER_ACTION_REMOVE, material, -delta);
            }
        }
    }

    private static Snapshot snapshot(AbstractContainerMenu menu) {
        Target target = null;
        Map<String, Integer> counts = new HashMap<>();
        Map<Container, Boolean> seen = new IdentityHashMap<>();
        for (Slot slot : menu.slots) {
            Container container = slot.container;
            if (container == null || container instanceof Inventory) {
                continue;
            }
            Target resolved = resolveTarget(container);
            if (resolved == null) {
                continue;
            }
            if (target == null) {
                target = resolved;
            }
            if (seen.put(container, Boolean.TRUE) != null) {
                continue;
            }
            for (int i = 0; i < container.getContainerSize(); i++) {
                add(counts, container.getItem(i));
            }
        }
        return target == null ? Snapshot.empty() : new Snapshot(target, counts);
    }

    private static Target resolveTarget(Container container) {
        if (container instanceof BlockEntity blockEntity) {
            return targetFromBlockEntity(blockEntity);
        }
        for (Field field : container.getClass().getDeclaredFields()) {
            if (!Container.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object nested = field.get(container);
                if (nested instanceof Container nestedContainer) {
                    Target target = resolveTarget(nestedContainer);
                    if (target != null) {
                        return target;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Target targetFromBlockEntity(BlockEntity blockEntity) {
        if (!(blockEntity.getLevel() instanceof ServerLevel level)) {
            return null;
        }
        var pos = blockEntity.getBlockPos();
        return new Target(worldName(level), pos.getX(), pos.getY(), pos.getZ());
    }

    private static void add(Map<String, Integer> counts, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
    }

    private static void log(GuardianDataManager manager, ServerPlayer player, Target target, int action, String material,
            int amount) {
        manager.logContainerActionAsync(
                player.getUUID().toString(),
                player.getName().getString(),
                action,
                target.world,
                target.x,
                target.y,
                target.z,
                material,
                amount,
                "dash");
    }

    private static String key(UUID uuid, AbstractContainerMenu menu) {
        return uuid + ":" + System.identityHashCode(menu);
    }

    private static String worldName(ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    private static String blockName(BlockState state) {
        return state == null ? "" : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private record Snapshot(Target target, Map<String, Integer> counts) {
        static Snapshot empty() {
            return new Snapshot(null, Map.of());
        }
    }

    private record Target(String world, int x, int y, int z) {
    }
}
