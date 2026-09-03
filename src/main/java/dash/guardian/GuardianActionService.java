package dash.guardian;

import dash.FabricDash;
import dash.data.GuardianDataManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class GuardianActionService {
    public ActionResult rollback(GuardianDataManager guardian, ActionRequest request) {
        return run("rollback", guardian, request);
    }

    public ActionResult restore(GuardianDataManager guardian, ActionRequest request) {
        return run("restore", guardian, request);
    }

    private ActionResult run(String mode, GuardianDataManager guardian, ActionRequest request) {
        boolean restore = "restore".equalsIgnoreCase(mode);
        List<GuardianDataManager.BlockLogEntry> blocks = request.includeBlocks()
                ? guardian.searchBlockLogsAdvanced(request.player(), request.world(), request.fromTime(), null,
                        GuardianDataManager.parseBlockAction(request.action()), request.x(), request.y(), request.z(),
                        request.radius(), request.include(), request.exclude(), 1, request.limit(), restore)
                : List.of();
        List<GuardianDataManager.ContainerLogEntry> containers = request.includeContainers()
                ? guardian.searchContainerLogsAdvanced(request.player(), request.world(), request.fromTime(), null,
                        GuardianDataManager.parseContainerAction(request.action()), request.x(), request.y(),
                        request.z(), request.radius(), request.include(), request.exclude(), 1, request.limit(), restore)
                : List.of();
        if (request.preview()) {
            return new ActionResult(true, mode, true, blocks.size(), containers.size(), 0, 0, 0,
                    "Preview matched " + blocks.size() + " block rows and " + containers.size()
                            + " container rows.");
        }
        MinecraftServer server = FabricDash.getServer();
        if (server == null) {
            return new ActionResult(false, mode, false, blocks.size(), containers.size(), 0, 0,
                    blocks.size() + containers.size(), "Server unavailable.");
        }
        CompletableFuture<ActionResult> future = new CompletableFuture<>();
        server.execute(() -> future.complete(apply(mode, server, blocks, containers)));
        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new ActionResult(false, mode, false, blocks.size(), containers.size(), 0, 0,
                    blocks.size() + containers.size(), "Guardian " + mode + " timed out: " + e.getMessage());
        }
    }

    private ActionResult apply(String mode, MinecraftServer server, List<GuardianDataManager.BlockLogEntry> blocks,
            List<GuardianDataManager.ContainerLogEntry> containers) {
        boolean restore = "restore".equalsIgnoreCase(mode);
        int changedBlocks = 0;
        int changedContainers = 0;
        int skipped = 0;
        for (GuardianDataManager.BlockLogEntry row : blocks) {
            if (applyBlock(server, row, restore)) changedBlocks++;
            else skipped++;
        }
        for (GuardianDataManager.ContainerLogEntry row : containers) {
            int changed = applyContainer(server, row, restore);
            if (changed > 0) changedContainers += changed;
            else skipped++;
        }
        return new ActionResult(true, mode, false, blocks.size(), containers.size(), changedBlocks, changedContainers,
                skipped, "Guardian " + mode + " changed " + changedBlocks + " blocks and " + changedContainers
                        + " container items.");
    }

    private boolean applyBlock(MinecraftServer server, GuardianDataManager.BlockLogEntry row, boolean restore) {
        ServerLevel level = world(server, row.world());
        if (level == null) return false;
        Block block = restore
                ? (row.action() == GuardianDataManager.ACTION_PLACE ? block(row.blockType()) : Blocks.AIR)
                : (row.action() == GuardianDataManager.ACTION_PLACE ? Blocks.AIR
                        : block(row.oldBlockType() == null ? row.blockType() : row.oldBlockType()));
        if (block == null) return false;
        return level.setBlock(new BlockPos(row.x(), row.y(), row.z()), block.defaultBlockState(), 3);
    }

    private int applyContainer(MinecraftServer server, GuardianDataManager.ContainerLogEntry row, boolean restore) {
        ServerLevel level = world(server, row.world());
        Item item = item(row.itemMaterial());
        if (level == null || item == null || item == Items.AIR || row.itemAmount() <= 0) return 0;
        if (!(level.getBlockEntity(new BlockPos(row.x(), row.y(), row.z())) instanceof Container container)) {
            return 0;
        }
        boolean add = restore
                ? row.action() == GuardianDataManager.CONTAINER_ACTION_ADD
                : row.action() == GuardianDataManager.CONTAINER_ACTION_REMOVE;
        return add ? addItem(container, item, row.itemAmount()) : removeItem(container, item, row.itemAmount());
    }

    private int addItem(Container container, Item item, int amount) {
        int remaining = amount;
        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && stack.is(item) && stack.getCount() < stack.getMaxStackSize()) {
                int add = Math.min(remaining, stack.getMaxStackSize() - stack.getCount());
                stack.grow(add);
                remaining -= add;
            }
        }
        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            if (container.getItem(i).isEmpty()) {
                int add = Math.min(remaining, item.getDefaultInstance().getMaxStackSize());
                container.setItem(i, new ItemStack(item, add));
                remaining -= add;
            }
        }
        container.setChanged();
        return amount - remaining;
    }

    private int removeItem(Container container, Item item, int amount) {
        int remaining = amount;
        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty() || !stack.is(item)) continue;
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            if (stack.isEmpty()) container.setItem(i, ItemStack.EMPTY);
            remaining -= take;
        }
        container.setChanged();
        return amount - remaining;
    }

    private ServerLevel world(MinecraftServer server, String worldName) {
        if (worldName == null || worldName.isBlank()) return null;
        for (ServerLevel level : server.getAllLevels()) {
            if (worldName.equals(level.dimension().identifier().toString())) {
                return level;
            }
        }
        return null;
    }

    private Block block(String raw) {
        Identifier id = identifier(raw);
        return id == null ? null : BuiltInRegistries.BLOCK.get(id).map(ref -> ref.value()).orElse(Blocks.AIR);
    }

    private Item item(String raw) {
        Identifier id = identifier(raw);
        return id == null ? null : BuiltInRegistries.ITEM.get(id).map(ref -> ref.value()).orElse(Items.AIR);
    }

    private Identifier identifier(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (!value.contains(":")) value = "minecraft:" + value;
        return Identifier.tryParse(value);
    }

    public record ActionRequest(String player, String world, Long fromTime, Integer x, Integer y, Integer z,
            Integer radius, String action, List<String> include, List<String> exclude, int limit, boolean preview,
            boolean includeBlocks, boolean includeContainers) {
    }

    public record ActionResult(boolean success, String mode, boolean preview, int matchedBlocks, int matchedContainers,
            int changedBlocks, int changedContainers, int skipped, String message) {
    }
}
