package dash;

/**
 * Sends player chat lines to Discord webhooks subscribed to the "chat" event.
 * In Fabric, this is called from FabricDash's ServerMessageEvents.CHAT_MESSAGE handler.
 */
public class DiscordWebhookChatListener {

    /**
     * Called when a player sends a chat message.
     *
     * @param playerName the name of the player
     * @param message    the chat message
     * @param worldName  the world the player is in
     */
    public static void onChatMessage(String playerName, String message, String worldName) {
        DiscordWebhookManager manager = FabricDash.getDiscordWebhookManager();
        if (manager == null) {
            return;
        }

        manager.dispatch(
                DiscordWebhookManager.EVENT_CHAT,
                "[" + worldName + "] <" + playerName + "> " + message);
    }
}
