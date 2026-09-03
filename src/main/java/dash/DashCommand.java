package dash;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import dash.web.PublicReportLinks;

public class DashCommand {

    private DashCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("dash")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(() ->
                                    Component.literal("Usage: /dash register [user] [rank] | /dash update | /dash port <port>"), false);
                            return 1;
                        })
                        .then(Commands.literal("update")
                                .executes(DashCommand::handleUpdate))
                        .then(Commands.literal("ticket")
                                .executes(ctx -> openPlayerReportPage(ctx, false, "")))
                        .then(Commands.literal("report")
                                .executes(ctx -> openPlayerReportPage(ctx, true, ""))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> openPlayerReportPage(ctx, true,
                                                StringArgumentType.getString(ctx, "player")))))
                        .then(Commands.literal("ui")
                                .executes(ctx -> handleIngameUi(ctx, "menu"))
                                .then(Commands.argument("action", StringArgumentType.word())
                                        .executes(ctx -> handleIngameUi(ctx, StringArgumentType.getString(ctx, "action")))))
                        .then(Commands.literal("port")
                                .requires(DashCommand::canUseOwnerCommands)
                                .then(Commands.argument("port", IntegerArgumentType.integer(1, 65535))
                                        .executes(DashCommand::handlePort)))
                        .then(Commands.literal("register")
                                .requires(DashCommand::canUseOwnerCommands)
                                .executes(DashCommand::handleRegisterSelf)
                                .then(Commands.argument("user", StringArgumentType.word())
                                        .executes(ctx -> handleRegisterTarget(ctx, null))
                                        .then(Commands.argument("rank", StringArgumentType.word())
                                                .executes(ctx -> handleRegisterTarget(ctx,
                                                        StringArgumentType.getString(ctx, "rank"))))))
        );
    }

    private static int openPlayerReportPage(CommandContext<CommandSourceStack> ctx, boolean report, String target) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command can only be used by players.").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!FeatureFlags.enabled("tickets")) {
            source.sendFailure(Component.literal("Tickets are currently disabled.").withStyle(ChatFormatting.RED));
            return 0;
        }
        String name = player.getName().getString();
        PublicReportLinks.CreatedLink link = PublicReportLinks.create(FabricDash.getDataDir(), name, 30,
                target == null ? "" : target, report ? "player" : "other");
        if (!link.success()) {
            source.sendFailure(Component.literal("The secure report page could not be created.").withStyle(ChatFormatting.RED));
            return 0;
        }
        String reportUrl = SetupNotifier.buildReportUrlStatic(FabricDash.getConfig(), link.token());
        if (reportUrl.isBlank()) {
            source.sendFailure(Component.literal(
                    "Set Public Report URL to this ForgeDash HTTPS domain in Plugin Settings first.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[Dash] Open secure report form")
                .withStyle(s -> s.withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent.OpenUrl(URI.create(reportUrl)))), false);
        source.sendSuccess(() -> Component.literal("Private one-time link. Expires in 30 minutes.")
                .withStyle(ChatFormatting.YELLOW), false);
        WebActionLogger.log("PUBLIC_REPORT_LINK_PLAYER", "reporter=" + name
                + (target == null || target.isBlank() ? "" : " target=" + target));
        return 1;
    }

    private static int handleIngameUi(CommandContext<CommandSourceStack> ctx, String action) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This UI can only be opened by a player."));
            return 0;
        }
        WebAuth webAuth = new WebAuth(FabricDash.getConfigDir(), FabricDash.LOGGER);
        if (!webAuth.isLinkedMainAdmin(player.getName().getString(), player.getStringUUID())) {
            source.sendFailure(Component.literal("Dash UI is restricted to the Minecraft account linked to MAIN_ADMIN.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        if ("confirm-restart".equalsIgnoreCase(action)) {
            WebActionLogger.log("INGAME_UI_RESTART", "linkedMainAdmin=" + player.getName().getString());
            source.sendSuccess(() -> Component.literal("[Dash] Restarting in two seconds.").withStyle(ChatFormatting.YELLOW), true);
            FabricDash.getInstance().getScheduler().schedule(() -> FabricDash.getServer().halt(false), 2,
                    java.util.concurrent.TimeUnit.SECONDS);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("━━━━━━━━ Dash MAIN_ADMIN ━━━━━━━━").withStyle(ChatFormatting.AQUA), false);
        source.sendSuccess(() -> Component.literal("Server: online  |  Players: "
                + FabricDash.getServer().getPlayerList().getPlayers().size() + "/" + FabricDash.getServer().getMaxPlayers())
                .withStyle(ChatFormatting.GREEN), false);
        source.sendSuccess(() -> Component.literal("Tickets: open the web inbox with /dash ui tickets"), false);
        source.sendSuccess(() -> Component.literal("Refresh: /dash ui   Restart: /dash ui restart"), false);
        if ("tickets".equalsIgnoreCase(action)) {
            String base = FabricDash.getConfig().getString("panel-url", "").replaceAll("/+$", "");
            if (!base.isBlank()) source.sendSuccess(() -> Component.literal("Open Tickets: " + base + "/staff")
                    .withStyle(s -> s.withColor(ChatFormatting.AQUA).withClickEvent(new ClickEvent.OpenUrl(URI.create(base + "/staff")))), false);
        } else if ("restart".equalsIgnoreCase(action)) {
            source.sendSuccess(() -> Component.literal("Confirmation required: /dash ui confirm-restart")
                    .withStyle(ChatFormatting.RED), false);
        }
        return 1;
    }

    static void notifyTicketOperators(String title, String reporter) {
        if (!FabricDash.getConfig().getBoolean("notifications.ingame.enabled", true)
                || !FabricDash.getConfig().getBoolean("notifications.ingame.tickets", true)
                || FabricDash.getServer() == null) return;
        List<String> recipients = FabricDash.getConfig().getStringList("notifications.ingame.users");
        Component notice = Component.literal("[Dash] " + title + " from " + reporter + ". Open the Tickets page.")
                .withStyle(ChatFormatting.AQUA);
        for (ServerPlayer online : FabricDash.getServer().getPlayerList().getPlayers()) {
            boolean selected = recipients.stream()
                    .anyMatch(name -> name.equalsIgnoreCase(online.getName().getString()));
            if (selected) online.sendSystemMessage(notice);
        }
    }

    static void notifySecurityOperators(String action, String operator) {
        if (!FabricDash.getConfig().getBoolean("notifications.ingame.enabled", true)
                || !FabricDash.getConfig().getBoolean("notifications.ingame.security", true)
                || FabricDash.getServer() == null) return;
        List<String> recipients = FabricDash.getConfig().getStringList("notifications.ingame.users");
        Component notice = Component.literal("[Dash Security] " + action + " by " + operator + ".")
                .withStyle(ChatFormatting.YELLOW);
        for (ServerPlayer online : FabricDash.getServer().getPlayerList().getPlayers()) {
            boolean selected = recipients.stream()
                    .anyMatch(name -> name.equalsIgnoreCase(online.getName().getString()));
            if (selected) online.sendSystemMessage(notice);
        }
    }

    private static int handleRegisterSelf(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command can only be used by players.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        if (!canUseOwnerCommands(source)) {
            source.sendFailure(Component.literal("Only OPs can use this command.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        RegistrationManager regManager = FabricDash.getRegistrationManager();
        String code = regManager.generateCode(player.getStringUUID(), player.getName().getString());
        String setupUrl = SetupNotifier.buildSetupUrlStatic(FabricDash.getConfig(), code);

        player.sendSystemMessage(Component.empty());
        player.sendSystemMessage(Component.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━").withStyle(ChatFormatting.AQUA));
        player.sendSystemMessage(Component.literal(" Dash Web Registration").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD));
        player.sendSystemMessage(Component.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━").withStyle(ChatFormatting.AQUA));
        player.sendSystemMessage(Component.empty());
        player.sendSystemMessage(Component.literal(" Your registration code:").withStyle(ChatFormatting.GRAY));
        player.sendSystemMessage(Component.literal(" " + code).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
        player.sendSystemMessage(Component.empty());
        player.sendSystemMessage(Component.literal(" Setup URL: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(setupUrl).withStyle(ChatFormatting.AQUA)));
        player.sendSystemMessage(Component.empty());
        player.sendSystemMessage(Component.literal(" This code expires in 5 minutes.").withStyle(ChatFormatting.YELLOW));
        player.sendSystemMessage(Component.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━").withStyle(ChatFormatting.AQUA));
        player.sendSystemMessage(Component.empty());

        WebActionLogger.log("REGISTER_CODE_GENERATED",
                "Player " + player.getName().getString() + " generated registration code");
        return 1;
    }

    private static int handleRegisterTarget(CommandContext<CommandSourceStack> ctx, String rankArg) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command can only be used by players.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        if (!canUseOwnerCommands(source)) {
            source.sendFailure(Component.literal("Only OPs can use this command.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        String targetName = StringArgumentType.getString(ctx, "user");
        String requestedRank = (rankArg != null) ? rankArg : "MODERATOR";
        String rank = requestedRank.toUpperCase(Locale.ROOT);

        RegistrationManager regManager = FabricDash.getRegistrationManager();
        String code = regManager.generateCode("UNBOUND", targetName, rank, List.of());
        String setupUrl = SetupNotifier.buildSetupUrlStatic(FabricDash.getConfig(), code);

        MinecraftServer server = FabricDash.getServer();
        ServerPlayer onlineTarget = server.getPlayerList().getPlayerByName(targetName);
        if (onlineTarget != null) {
            onlineTarget.sendSystemMessage(Component.empty());
            onlineTarget.sendSystemMessage(
                    Component.literal("[Dash] ").withStyle(ChatFormatting.AQUA)
                            .append(Component.literal("Du wurdest von " + player.getName().getString()
                                    + " in das Dash-Panel eingeladen! Dein Rang: " + rank
                                    + ". Klicke hier, um dich zu registrieren.")
                                    .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
                                    .withStyle(s -> s.withClickEvent(
                                            new ClickEvent.OpenUrl(URI.create(setupUrl))))));
            onlineTarget.sendSystemMessage(Component.literal("Setup URL: " + setupUrl).withStyle(ChatFormatting.GRAY));
            onlineTarget.sendSystemMessage(Component.literal("Code expires in 5 minutes.").withStyle(ChatFormatting.RED));
        }

        player.sendSystemMessage(Component.literal("Invite generated for " + targetName + " (" + rank + ").")
                .withStyle(ChatFormatting.GREEN));
        player.sendSystemMessage(Component.literal("Code: " + code).withStyle(ChatFormatting.AQUA));
        player.sendSystemMessage(Component.literal("Setup URL: " + setupUrl).withStyle(ChatFormatting.AQUA));
        if (onlineTarget == null) {
            player.sendSystemMessage(Component.literal("Target player not online; invite remains code-based.")
                    .withStyle(ChatFormatting.YELLOW));
        }

        WebActionLogger.log("REGISTER_CODE_GENERATED",
                "Player " + player.getName().getString() + " generated invite for " + targetName + " role=" + rank);
        return 1;
    }

    private static int handlePort(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int newPort = IntegerArgumentType.getInteger(ctx, "port");

        source.sendSuccess(() -> Component.literal("[ForgeDash] Changing web server port to " + newPort + "...")
                .withStyle(ChatFormatting.YELLOW), true);

        FabricDash.getInstance().changeWebPort(newPort);

        AdminWebServer ws = FabricDash.getInstance().getWebServer();
        if (ws != null && !ws.hasPortBindFailed()) {
            source.sendSuccess(() -> Component.literal("[ForgeDash] Web server now running on port " + newPort)
                    .withStyle(ChatFormatting.GREEN), true);
        } else {
            source.sendFailure(Component.literal("[ForgeDash] Failed to bind to port " + newPort + ". Try another port.")
                    .withStyle(ChatFormatting.RED));
        }
        return 1;
    }

    private static int handleUpdate(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        boolean isConsole = source.getEntity() == null;
        if (!isConsole && !canUseOwnerCommands(source)) {
            source.sendFailure(Component.literal("Only OPs or console can use /dash update.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        GithubUpdater updater = FabricDash.getGithubUpdater();
        if (updater == null || !updater.isEnabled()) {
            source.sendFailure(Component.literal("Updater is currently unavailable. Check console logs for details.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Downloading Dash update...").withStyle(ChatFormatting.YELLOW), false);
        CompletableFuture.runAsync(() -> {
            boolean ok = updater.downloadUpdate();
            MinecraftServer server = FabricDash.getServer();
            if (server != null) {
                server.execute(() -> {
                    if (ok) {
                        source.sendSuccess(() -> Component.literal(
                                "Update downloaded! Dash will be updated on the next server restart.")
                                .withStyle(ChatFormatting.GREEN), false);
                    } else {
                        source.sendFailure(Component.literal(
                                "Update download failed. Check console logs for updater errors.")
                                .withStyle(ChatFormatting.RED));
                    }
                });
            }
        });
        return 1;
    }

    private static boolean canUseOwnerCommands(CommandSourceStack source) {
        return source.getEntity() == null || source.permissions().hasPermission(Permissions.COMMANDS_OWNER);
    }
}
