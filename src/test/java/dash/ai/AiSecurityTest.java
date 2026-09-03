package dash.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class AiSecurityTest {
    private AiSecurityTest() { }

    public static void run() throws Exception {
        testRedaction();
        testVault();
        testProviderAndManager();
    }

    private static void testRedaction() {
        String source = "Authorization: Bearer abcdefghijklmnopqrstuvwxyz api_key=super-secret-value "
                + "owner@example.com 192.168.1.20 AIzaSyABCDEFGHIJKLMNOPQRSTUVWXYZ123456";
        String clean = AiRedactor.redact(source, 10_000);
        check(!clean.contains("owner@example.com"), "email must be redacted");
        check(!clean.contains("192.168.1.20"), "IP must be redacted");
        check(!clean.contains("super-secret-value"), "assigned secret must be redacted");
        check(!clean.contains("AIzaSy"), "Google key must be redacted");
    }

    private static void testVault() throws Exception {
        Path dir = Files.createTempDirectory("dash-ai-vault-");
        String key = "AIzaSyDashTestKey012345678901234567890";
        AiSecretVault vault = new AiSecretVault(dir, Map.of());
        vault.save(key);
        check(key.equals(vault.load()), "vault must decrypt its stored key");
        check(!Files.readString(dir.resolve("ai-agent.key")).contains(key), "vault key file must not expose provider key");
        check(!Files.readString(dir.resolve("ai-secrets.dat")).contains(key), "encrypted secret must not contain plaintext");
        AiSecretVault environment = new AiSecretVault(dir, Map.of("GOOGLE_API_KEY", "environment-key-012345678901"));
        check(environment.load().startsWith("environment-key"), "environment key must override vault storage");
        vault.removeStoredSecret();
        check(vault.load().isBlank(), "removing the stored secret must disable local key loading");
    }

    private static void testProviderAndManager() throws Exception {
        String quotaError = GeminiInteractionsClient.providerError(429,
                "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\",\"message\":\"Daily model quota is unavailable.\","
                        + "\"details\":[{\"violations\":[{\"quotaMetric\":\"generativelanguage.googleapis.com/test_daily_requests\"}]},{\"retryDelay\":\"42s\"}]}}");
        check(quotaError.contains("RESOURCE_EXHAUSTED") && quotaError.contains("test_daily_requests")
                        && quotaError.contains("42s"),
                "provider quota diagnostics must preserve Google's concrete reason");
        check(!quotaError.contains("rate limit reached"), "HTTP 429 must not be mislabeled as request-rate usage");
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> header = new AtomicReference<>("");
        AtomicReference<String> request = new AtomicReference<>("");
        AtomicReference<String> transcriptRequest = new AtomicReference<>("");
        AtomicInteger modelChecks = new AtomicInteger();
        AtomicInteger managerRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/interactions", exchange -> {
            header.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            String incoming = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            request.set(incoming);
            JsonObject incomingJson = JsonParser.parseString(incoming).getAsJsonObject();
            boolean hello = incomingJson.get("input").isJsonPrimitive()
                    && "hello".equals(incomingJson.get("input").getAsString());
            boolean health = incomingJson.get("input").isJsonPrimitive()
                    && "health".equals(incomingJson.get("input").getAsString());
            int attempt = hello ? calls.incrementAndGet() : 2;
            if (!hello) managerRequests.incrementAndGet();
            if (!hello && !health) transcriptRequest.set(incoming);
            String body = hello && attempt == 1 ? "{\"error\":{\"message\":\"retry\"}}"
                    : health ? "{\"id\":\"interaction-tool-1\",\"status\":\"requires_action\",\"steps\":[{\"type\":\"function_call\",\"id\":\"call-1\",\"name\":\"get_server_overview\",\"arguments\":{}}]}"
                    : "{\"id\":\"interaction-1\",\"steps\":[{\"type\":\"model_output\",\"content\":[{\"type\":\"text\",\"text\":\"Dash AI ready\"}]}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(attempt == 1 ? 429 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/models/gemini-3.7-flash", exchange -> {
            check("GET".equals(exchange.getRequestMethod()), "key verification must use a metadata GET");
            check("server-only-key-0123456789".equals(exchange.getRequestHeaders().getFirst("x-goog-api-key")),
                    "metadata verification must keep the key in the server header");
            modelChecks.incrementAndGet();
            byte[] bytes = "{\"name\":\"models/gemini-3.7-flash\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/interactions");
            GeminiInteractionsClient provider = new GeminiInteractionsClient(HttpClient.newHttpClient(), endpoint);
            provider.verifyKey("server-only-key-0123456789", "gemini-3.7-flash");
            check(modelChecks.get() == 1, "key test must use model metadata instead of billable inference");
            var response = provider.create("server-only-key-0123456789", "unsupported-model",
                    new JsonPrimitive("hello"), new JsonArray(), "", "Stay careful");
            check("Dash AI ready".equals(response.outputText()), "fake provider response must parse");
            check(calls.get() == 2, "429 responses must be retried within the bound");
            check("server-only-key-0123456789".equals(header.get()), "provider key must use the server header");
            check(request.get().contains("gemini-3.7-flash"), "unsupported model must fall back safely");
            check(request.get().contains("\"store\":false"), "provider storage must remain disabled");
            JsonObject payload = JsonParser.parseString(request.get()).getAsJsonObject();
            check(payload.get("input").isJsonPrimitive() && "hello".equals(payload.get("input").getAsString()),
                    "user input must use the Interactions API string format");
            check("Stay careful".equals(payload.get("system_instruction").getAsString()),
                    "system instructions must use their dedicated provider field");

            Path dir = Files.createTempDirectory("dash-ai-manager-");
            try (AiAgentManager manager = new AiAgentManager(dir, provider)) {
                check(!manager.status("alice").enabled(), "AI must default to disabled");
                check(manager.submitChat("alice", "", "hello", "", false, Set.of("*"), (tool, args, user) -> "ok")
                        .handle((value, error) -> error != null).get(5, TimeUnit.SECONDS), "disabled AI must reject chats");
                manager.acceptTerms("owner", "MAIN_ADMIN", true);
                manager.acceptTerms("alice", "ADMIN", false);
                String configured = manager.configure(true, false, "gemini-3.7-flash",
                        "AIzaSyManagerTest012345678901234567890", false);
                check(configured.contains("enabled"), "consented owner can enable AI");
                var chat = manager.submitChat("alice", "", "health", "selected context", false, Set.of("*"),
                        (tool, args, user) -> "bounded").get(10, TimeUnit.SECONDS);
                check("Dash AI ready".equals(chat.response()), "advisory response must persist");
                check(managerRequests.get() == 2, "function tools must complete in one bounded follow-up");
                JsonObject transcript = JsonParser.parseString(transcriptRequest.get()).getAsJsonObject();
                check(!transcript.has("previous_interaction_id"), "stateless mode must not send a previous interaction id");
                check(transcript.get("input").isJsonArray(), "stateless tool follow-up must send local history");
                String history = transcript.getAsJsonArray("input").toString();
                check(history.contains("user_input") && history.contains("function_call")
                                && history.contains("function_result"),
                        "local history must contain the prompt, tool call and sanitized result");
                check(manager.messages("bob", chat.conversationId(), 20).isEmpty(), "conversation data must be isolated by user");
                check(manager.messages("alice", chat.conversationId(), 20).size() == 2, "owner must see own prompt and answer");
                manager.revokeConsent("alice");
                check(!manager.status("alice").userAccepted(), "revoked user consent must take effect immediately");
            }
        } finally {
            server.stop(0);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("AI security test failed: " + message);
    }
}
