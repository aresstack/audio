package com.aresstack.audio.studio.ai;

import com.aresstack.audio.instruments.network.AudioProxyRoutes;
import com.aresstack.audio.instruments.network.AudioWindowsTrust;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URI;
import javax.net.ssl.HttpsURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Isolate the authenticated OpenAI-compatible KIPITZ protocol from music composition. */
public final class KipitzChatClient implements ChatModel {
    private final Gson gson = new Gson();
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final AudioProxyRoutes routes;
    private final String proxyHost;
    private final int proxyPort;
    private final java.util.Set<HttpURLConnection> activeConnections =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<HttpURLConnection, Boolean>());

    public KipitzChatClient(String endpoint, String apiKey, String model, String proxyHost, int proxyPort) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.proxyHost = proxyHost == null ? "" : proxyHost.trim();
        this.proxyPort = proxyPort;
        this.routes = null;
    }

    public KipitzChatClient(String endpoint, String apiKey, String model, AudioProxyRoutes routes) {
        if (routes == null) {
            throw new IllegalArgumentException("Proxy route resolver cannot be null.");
        }
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.proxyHost = "";
        this.proxyPort = 0;
        this.routes = routes;
    }

    public JsonObject generate(String instruction, String prompt, int maxTokens, Path cache) throws IOException {
        return generate(instruction, prompt, maxTokens, cache, null);
    }

    /** Answers and lets the answer be read while it arrives, see {@link ChatModel#generate(String, String, int, Path, java.util.function.Consumer)}. */
    @Override
    public JsonObject generate(String instruction, String prompt, int maxTokens, Path cache,
                               java.util.function.Consumer<String> heard) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("AI request was cancelled.");
        }
        AnswerOrigin origin = origin(instruction, prompt, maxTokens);
        JsonObject kept = AnswerCache.reuse(cache, origin, legacyModel);
        if (kept != null) {
            return kept;
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IOException("Enter an API key to generate the missing composition part.");
        }
        // From here on the model is asked: the studio's AI lamp is on for as long as that lasts.
        busy(+1);
        try {
            return ask(instruction, prompt, maxTokens, cache, heard, origin);
        } finally {
            busy(-1);
        }
    }

    /** Hears when a request to the model begins (+1) and ends (-1); e.g. the AI lamp of the studio. */
    private volatile java.util.function.IntConsumer activity;

    /** Tells this listener when a request to the model begins (+1) and ends (-1), whatever asks. */
    public KipitzChatClient activity(java.util.function.IntConsumer listener) {
        this.activity = listener;
        return this;
    }

    private void busy(int change) {
        java.util.function.IntConsumer listener = activity;
        if (listener != null) {
            try {
                listener.accept(change);
            } catch (RuntimeException unheard) {
                // the lamp must never break a request
            }
        }
    }

    private JsonObject ask(String instruction, String prompt, int maxTokens, Path cache,
                           java.util.function.Consumer<String> heard, AnswerOrigin origin) throws IOException {
        JsonObject request = new JsonObject();
        request.addProperty("model", model);
        request.addProperty("temperature", TEMPERATURE);
        request.addProperty("top_p", TOP_P);
        request.addProperty("top_k", TOP_K);
        request.addProperty("max_tokens", maxTokens);
        request.addProperty("user", "audio-swing-studio");
        if (reasoningEffort != null) {
            request.addProperty("reasoning_effort", reasoningEffort); // without: the model decides
        }
        JsonArray messages = new JsonArray();
        messages.add(message("system", instruction));
        messages.add(message("user", prompt));
        request.add("messages", messages);

        int budget = maxTokens;
        int longer = 0;
        int troubled = 0;
        for (int attempt = 1; ; attempt++) {
            // Streamed: the answer arrives piece by piece while the model writes. A proxy or gateway
            // on the way gives up on a connection that stays silent (HTTP 504), and a long answer
            // keeps a connection silent for minutes when it only comes at the end.
            request.addProperty("stream", streaming);
            request.addProperty("max_tokens", budget);
            String trouble;
            try {
                Answer answer = gated(gson.toJson(request).getBytes(StandardCharsets.UTF_8), heard);
                if ("length".equals(answer.finishReason) && longer < longerAnswers
                        && !cancelled && !Thread.currentThread().isInterrupted()) {
                    // Cut off at the limit: a model that thinks before it answers spends the tokens of
                    // its answer on that. Asked again with more room (studio.properties: longerAnswerFactor).
                    longer++;
                    budget *= longerFactor;
                    continue;
                }
                if (!"stop".equals(answer.finishReason)) {
                    throw new IOException("The model output was not complete (finish_reason=" + answer.finishReason
                            + ("length".equals(answer.finishReason) ? ", also with " + budget + " tokens" : "")
                            + ", model " + model + ").");
                }
                String content = answer.text.trim();
                JsonObject parsed;
                try {
                    parsed = jsonObjectIn(content);
                } catch (RuntimeException malformed) {
                    parsed = null;
                }
                if (parsed != null) {
                    AnswerCache.store(cache, origin, gson.toJson(parsed));
                    return parsed;
                }
                // An answer without a JSON object is not kept; the model is asked again.
                trouble = "Antwort ohne JSON-Objekt (\"" + glimpse(content) + "\")";
            } catch (Refused refused) {
                if (longer > 0) {
                    throw new IOException("The model output was not complete (finish_reason=length), and the "
                            + "service refused a longer answer of " + budget + " tokens (HTTP " + refused.status
                            + "). Choose a shorter piece or a model with a longer answer.");
                }
                if (streaming && (refused.status == 400 || refused.status == 404 || refused.status == 415
                        || refused.status == 422 || refused.status == 501)) {
                    streaming = false; // a service that does not stream: asked the old way from now on
                    continue;
                }
                if (refused.status != 408 && refused.status != 429 && refused.status < 500) {
                    throw new IOException("KIPITZ returned HTTP " + refused.status
                            + ". Check endpoint, proxy, credentials and model.");
                }
                trouble = "HTTP " + refused.status; // busy or a gateway in between: asked again later
            } catch (Unusable unusable) {
                trouble = unusable.getMessage(); // an answer that cannot be read is not kept
            } catch (IOException broken) {
                if (broken.getMessage() != null && broken.getMessage().startsWith("The model output was not complete")) {
                    throw broken;
                }
                trouble = broken.getMessage(); // the connection failed or broke off on the way
            }
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new java.io.InterruptedIOException("KI-Anfrage abgebrochen.");
            }
            // Never given up: tried again after a pause that grows up to its longest (studio.properties:
            // retryFirstSeconds, retryMostSeconds); only cancelling ends it.
            troubled++;
            if (Backoff.givesUp(troubled)) {
                throw new IOException("Die KI-Anfrage ist " + troubled + "-mal gescheitert: " + trouble);
            }
            long wait = Backoff.delay(troubled);
            tell("Verbindung zur KI: " + trouble + " · Versuch " + (troubled + 1) + " in " + Backoff.words(wait)
                    + " (" + name() + ").");
            Backoff.pause(troubled, () -> cancelled);
        }
    }

    /**
     * One exchange through the gate of the model: no more requests at once than the connections
     * hold now; one that breaks off or finds the service busy makes the gate narrower.
     */
    private Answer gated(byte[] body, java.util.function.Consumer<String> heard) throws IOException {
        ConnectionGate gate = ConnectionGate.of(model);
        long entered = gate.enter(() -> cancelled);
        boolean broke = false;
        try {
            return exchange(body, heard);
        } catch (Refused refused) {
            broke = refused.status == 408 || refused.status == 429 || refused.status >= 500;
            throw refused;
        } catch (Unusable unusable) {
            throw unusable; // the connection held; the answer is the trouble
        } catch (IOException failed) {
            broke = !cancelled && !Thread.currentThread().isInterrupted();
            throw failed;
        } finally {
            gate.leave(entered, broke);
        }
    }

    /**
     * The JSON object in a model's answer. First the span from the first '{' to the last '}', as a
     * plain answer has it; if that does not parse (a thinking part with braces before it, a code
     * block, words with braces after it), every balanced object in the text, the last one that
     * parses first, since the answer comes after the thinking. {@code null} if there is none.
     */
    static JsonObject jsonObjectIn(String content) {
        if (content == null) {
            return null;
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        JsonObject whole = object(content.substring(start, end + 1));
        if (whole != null) {
            return whole;
        }
        List<String> found = new ArrayList<String>();
        for (int at = 0; at < content.length(); at++) {
            if (content.charAt(at) != '{') {
                continue;
            }
            int close = closing(content, at);
            if (close > at) {
                found.add(content.substring(at, close + 1));
                at = close;
            }
        }
        for (int each = found.size() - 1; each >= 0; each--) {
            JsonObject one = object(found.get(each));
            if (one != null) {
                return one;
            }
        }
        return null;
    }

    /** Where the object opened at {@code open} closes, strings respected; -1 if it does not. */
    private static int closing(String text, int open) {
        int depth = 0;
        boolean inString = false;
        for (int at = open; at < text.length(); at++) {
            char c = text.charAt(at);
            if (inString) {
                if (c == '\\') {
                    at++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return at;
                }
            }
        }
        return -1;
    }

    private static JsonObject object(String text) {
        try {
            com.google.gson.JsonElement element = new JsonParser().parse(text);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException unusable) {
            return null;
        }
    }

    /**
     * The answer in a response that came whole, not streamed. A model that thinks first (gpt-oss)
     * may send its thinking apart and no content at all ({@code null}) when it was cut off while
     * thinking: that is an empty answer with its finish reason, which asks again with more room,
     * not an unreadable response. {@code null} only if there is no answer in it at all.
     */
    static Answer wholeAnswer(String response) {
        try {
            JsonObject envelope = new JsonParser().parse(response).getAsJsonObject();
            if (envelope.has("error")) {
                return null;
            }
            JsonObject choice = envelope.getAsJsonArray("choices").get(0).getAsJsonObject();
            com.google.gson.JsonElement message = choice.get("message");
            com.google.gson.JsonElement content = message != null && message.isJsonObject()
                    ? message.getAsJsonObject().get("content") : choice.get("text");
            String text = content != null && content.isJsonPrimitive() ? content.getAsString() : "";
            com.google.gson.JsonElement finish = choice.get("finish_reason");
            String reason = finish != null && finish.isJsonPrimitive() ? finish.getAsString() : "stop";
            return new Answer(text, reason);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /** The start of an answer, on one line, for a message. */
    static String glimpse(String content) {
        String line = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        return line.length() <= 200 ? line : line.substring(0, 200) + " …";
    }

    /** How often an answer that was cut off at its limit is asked for again, each time with twice the room. */
    public static final int LONGER_ANSWERS = 3;

    /** By how much the room grows each time, by default. */
    public static final int LONGER_FACTOR = 2;

    private volatile int longerAnswers = LONGER_ANSWERS;
    private volatile int longerFactor = LONGER_FACTOR;

    /** How often a cut-off answer is asked for again, and by how much its room grows each time. */
    public KipitzChatClient longerAnswers(int times, int factor) {
        this.longerAnswers = Math.max(0, times);
        this.longerFactor = Math.max(2, factor);
        return this;
    }

    /** The sampling of every request; part of the identity of a kept answer. */
    static final double TEMPERATURE = 0.55;
    static final double TOP_P = 0.9;
    static final int TOP_K = 40;

    /**
     * The model an answer kept before origins were written down is taken to come from: the
     * studio's single model of before. Until told otherwise the studio's default.
     */
    private volatile String legacyModel = FORMER_DEFAULT_MODEL;

    /** The studio's default model ({@code AiDefaults.MODEL}, not imported: settings sit above the AI). */
    static final String FORMER_DEFAULT_MODEL = "google/gemma-4-31B-it";

    /**
     * Says which model the answers kept without an origin came from, normally the {@code model}
     * of the settings. Such an answer is given again only when this client asks that model.
     */
    public KipitzChatClient legacyModel(String model) {
        this.legacyModel = model == null || model.isBlank() ? FORMER_DEFAULT_MODEL : model.trim();
        return this;
    }

    @Override
    public String name() {
        return model == null ? "" : model.trim();
    }

    @Override
    public boolean hasAnswer(String instruction, String prompt, int maxTokens, Path cache) {
        return AnswerCache.reusable(cache, origin(instruction, prompt, maxTokens), legacyModel);
    }

    private AnswerOrigin origin(String instruction, String prompt, int maxTokens) {
        return new AnswerOrigin(name(), instruction, prompt, maxTokens, TEMPERATURE, TOP_P, TOP_K);
    }

    /** How often a request is sent again when the connection fails or breaks off, before the failure counts. */

    /** How much this model thinks before it answers: low, medium or high; {@code null} lets it decide. */
    private volatile String reasoningEffort;

    /** Sets how much this model thinks before it answers, sent as {@code reasoning_effort}; {@code null}: it decides. */
    public KipitzChatClient reasoningEffort(String effort) {
        String value = effort == null ? "" : effort.trim().toLowerCase(java.util.Locale.ROOT);
        this.reasoningEffort = ModelAssignment.EFFORTS.contains(value) ? value : null;
        return this;
    }

    /** The wait for the first text of an answer by default, in seconds: a busy service lets requests queue. */
    public static final int FIRST_TEXT_SECONDS = 300;
    /** The silence allowed in the middle of an answer by default, in seconds. */
    public static final int SILENCE_SECONDS = 90;
    /** How long a request may stay without its first text: the service may let it wait in a queue. */
    static volatile long firstByteMillis = FIRST_TEXT_SECONDS * 1000L;
    /** How long an answer whose text has begun may stay silent before the connection counts as dead. */
    static volatile long silenceMillis = SILENCE_SECONDS * 1000L;

    /**
     * Sets how long the first text of an answer may take and how long an answer may then stay
     * silent, in seconds (studio.properties: {@code firstTextSeconds}, {@code silenceSeconds}).
     */
    public static void waits(int firstTextSeconds, int silenceSeconds) {
        firstByteMillis = Math.max(1, firstTextSeconds) * 1000L;
        silenceMillis = Math.max(1, silenceSeconds) * 1000L;
    }

    /** Hears what goes wrong on the way and what is done about it; never the answer itself. */
    private volatile java.util.function.Consumer<String> troubles;

    /**
     * Tells this listener when a connection breaks off, stays silent or is tried again, so that a
     * wait is not taken for a standstill. Called on the thread that asks.
     */
    public KipitzChatClient troubles(java.util.function.Consumer<String> listener) {
        this.troubles = listener;
        return this;
    }

    private void tell(String line) {
        java.util.function.Consumer<String> listener = troubles;
        if (listener != null) {
            try {
                listener.accept(line);
            } catch (RuntimeException unheard) {
                // telling about trouble must not make more of it
            }
        }
    }

    /**
     * Watches the connections that wait for an answer and ends one that stays silent too long. The
     * timeout of the socket alone is not enough: a proxy layer may not honour it, and a dead Wi-Fi
     * connection then holds a request for ever.
     */
    private static final java.util.concurrent.ScheduledExecutorService WATCH =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "kipitz-connection-watch");
                thread.setDaemon(true);
                return thread;
            });

    /** One request on its way: when it last gave a sign of life, and whether the watch ended it. */
    private static final class Pulse {
        volatile long last = System.currentTimeMillis();
        volatile boolean begun;
        volatile String ended;

        /** A sign of life: the service answered or sent something, maybe only to keep the line open. */
        void alive() {
            last = System.currentTimeMillis();
        }

        /** Text of the answer arrived: from now on it is expected to flow. */
        void beat() {
            last = System.currentTimeMillis();
            begun = true;
        }
    }

    /** A stream that notes every byte that arrives as a sign of life. */
    private static final class Watched extends java.io.FilterInputStream {
        private final Pulse pulse;

        Watched(InputStream in, Pulse pulse) {
            super(in);
            this.pulse = pulse;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            pulse.alive();
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0 && !pulse.begun && begins(buffer, offset, count)) {
                pulse.beat();
            } else if (count > 0 && pulse.begun) {
                pulse.beat();
            } else {
                pulse.alive();
            }
            return count;
        }

        /**
         * Whether these bytes carry text of the answer, thinking included, and not only a sign that
         * the line is open: a request may wait in the service's queue behind a first line of headers
         * or keep-alive comments, and that wait has its own, longer limit.
         */
        private static boolean begins(byte[] buffer, int offset, int count) {
            String seen = new String(buffer, offset, count, StandardCharsets.UTF_8);
            return seen.contains("\"content\":\"") || seen.contains("\"reasoning_content\":\"")
                    || seen.contains("\"reasoning\":\"") || seen.contains("\"thinking\":\"") || seen.contains("\"choices\"") && seen.contains("\"message\"");
        }
    }
    private static final int RESPONSE_LIMIT = 2_000_000;

    /** Whether answers are asked for as a stream; switched off for a service that refuses to stream. */
    private volatile boolean streaming = true;
    private volatile boolean cancelled;

    /** The text of an answer and why the model ended it. */
    static final class Answer {
        final String text;
        final String finishReason;

        Answer(String text, String finishReason) {
            this.text = text;
            this.finishReason = finishReason;
        }
    }

    /** The service answered with an error status: sending the same request again would not help. */
    private static final class Refused extends IOException {
        private final int status;

        Refused(int status) {
            super("HTTP " + status);
            this.status = status;
        }
    }

    /** The service answered, but not with what can be read as an answer: asking again would not help either. */
    private static final class Unusable extends IOException {
        Unusable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Sends one request and reads its answer, streamed or whole, whichever the service sends. */
    private Answer exchange(byte[] body, java.util.function.Consumer<String> heard) throws IOException {
        final HttpURLConnection connection = openConnection();
        activeConnections.add(connection);
        final Pulse pulse = new Pulse();
        final Thread asking = Thread.currentThread();
        java.util.concurrent.ScheduledFuture<?> watch = WATCH.scheduleWithFixedDelay(() -> {
            if (asking.isInterrupted() && pulse.ended == null) {
                // The one who asked stopped (e.g. the other tracks of a piece whose track failed):
                // only this request ends, the client keeps serving every other one.
                pulse.ended = "die Anfrage wurde abgebrochen";
                connection.disconnect();
                return;
            }
            long silent = System.currentTimeMillis() - pulse.last;
            long allowed = pulse.begun ? silenceMillis : firstByteMillis;
            if (silent > allowed && pulse.ended == null) {
                pulse.ended = pulse.begun
                        ? "die KI schweigt seit " + silent / 1000 + " s mitten in der Antwort (Grenze „Stille“ in den "
                        + "Einstellungen: " + allowed / 1000 + " s), die Verbindung gilt als abgerissen"
                        : "nach " + silent / 1000 + " s noch kein Text der Antwort (Grenze „Erste Antwort“ in den "
                        + "Einstellungen: " + allowed / 1000 + " s)";
                connection.disconnect(); // the read that hangs ends with an error and the request is sent again
            }
        }, 2, 2, java.util.concurrent.TimeUnit.SECONDS);
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(300000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json, text/event-stream");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new Refused(status);
            }
            String type = String.valueOf(connection.getContentType());
            pulse.alive(); // the service answered; the text may still wait in its queue
            try (InputStream input = new Watched(connection.getInputStream(), pulse)) {
                if (type.contains("event-stream")) {
                    return readStream(input, heard);
                }
                String response = readText(input, RESPONSE_LIMIT);
                Answer whole = wholeAnswer(response);
                if (whole == null) {
                    throw new Unusable("Could not parse the KIPITZ model response (\"" + glimpse(response) + "\").",
                            null);
                }
                return whole;
            }
        } catch (Refused | Unusable told) {
            throw told;
        } catch (IOException broken) {
            if (pulse.ended != null) {
                throw new IOException(pulse.ended, broken); // said in words, not as "Socket closed"
            }
            throw broken;
        } finally {
            watch.cancel(false);
            activeConnections.remove(connection);
            connection.disconnect();
        }
    }

    /**
     * Reads a streamed answer: lines {@code data: {...}} with a piece of text each in
     * {@code choices[0].delta.content}, ended by {@code data: [DONE]}. Reading stops at that end
     * mark instead of waiting for the connection to close, because a proxy may cut the connection
     * without ending it properly ("Premature EOF"). If the connection breaks after the model has
     * said why it ended, the answer is whole and is taken; if it breaks earlier, that is an error
     * and the request is sent again. Thinking arrives in other fields and is left out.
     */
    static Answer readStream(InputStream input) throws IOException {
        return readStream(input, null);
    }

    /** The time between two looks at an answer that is still arriving, in milliseconds. */
    private static final long HEARD_EVERY = 200;

    /**
     * Reads a streamed answer as {@link #readStream(InputStream)} does and shows the text so far
     * to a listener while it arrives, a few times a second at most.
     */
    static Answer readStream(InputStream input, java.util.function.Consumer<String> heard) throws IOException {
        StringBuilder text = new StringBuilder();
        long lastHeard = 0;
        String finishReason = null;
        String broken = null;
        boolean done = false;
        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
        try {
            for (String line = reader.readLine(); line != null && !done; line = reader.readLine()) {
                if (!line.startsWith("data:")) {
                    continue; // empty lines between events, comments that keep the connection alive
                }
                String data = line.substring(5).trim();
                if (data.equals("[DONE]")) {
                    done = true;
                    break;
                }
                if (data.isEmpty()) {
                    continue;
                }
                JsonObject event;
                try {
                    event = new JsonParser().parse(data).getAsJsonObject();
                } catch (RuntimeException malformed) {
                    throw new Unusable("The stream carried something that is no JSON.", malformed);
                }
                if (event.has("error")) {
                    throw new Unusable("KIPITZ reported: " + event.get("error"), null);
                }
                if (!event.has("choices") || !event.get("choices").isJsonArray()
                        || event.getAsJsonArray("choices").size() == 0) {
                    continue; // e.g. the usage at the end
                }
                JsonObject choice = event.getAsJsonArray("choices").get(0).getAsJsonObject();
                if (choice.has("delta") && choice.get("delta").isJsonObject()) {
                    com.google.gson.JsonElement content = choice.getAsJsonObject("delta").get("content");
                    if (content != null && content.isJsonPrimitive()) {
                        text.append(content.getAsString());
                        if (text.length() > RESPONSE_LIMIT) {
                            throw new Unusable("KIPITZ response exceeds the configured size limit.", null);
                        }
                        long now = System.currentTimeMillis();
                        if (heard != null && now - lastHeard >= HEARD_EVERY) {
                            lastHeard = now;
                            try {
                                heard.accept(text.toString());
                            } catch (RuntimeException unheard) {
                                // looking at the answer on its way must never break the answer
                            }
                        }
                    }
                }
                com.google.gson.JsonElement finish = choice.get("finish_reason");
                if (finish != null && finish.isJsonPrimitive()) {
                    finishReason = finish.getAsString();
                }
            }
            if (!done) {
                broken = "the connection was closed"; // quietly, without the end mark
            }
        } catch (Unusable unusable) {
            throw unusable;
        } catch (IOException cut) {
            broken = String.valueOf(cut.getMessage()); // "Premature EOF", a reset, a silence too long
        }
        if (finishReason == null) {
            throw new IOException("The stream broke off after " + text.length() + " characters: " + broken);
        }
        return new Answer(text.toString(), finishReason);
    }

    /** Disconnect a pending request when the user cancels the composition. */
    public void cancel() {
        cancelled = true;
        for (HttpURLConnection connection : activeConnections) {
            connection.disconnect();
        }
    }

    /** Open through the PAC-aware proxy route and the Windows trust store. */
    private HttpURLConnection openConnection() throws IOException {
        return openConnection(URI.create(endpoint));
    }

    private HttpURLConnection openConnection(URI destination) throws IOException {
        URL url = destination.toURL();
        if (!"https".equalsIgnoreCase(url.getProtocol()) && !"http".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Only HTTP(S) endpoints are supported.");
        }
        HttpURLConnection connection;
        if (routes != null) {
            connection = (HttpURLConnection) url.openConnection(routes.proxyFor(destination));
        } else if (proxyHost.isEmpty()) {
            connection = (HttpURLConnection) url.openConnection();
        } else {
            if (proxyPort < 1 || proxyPort > 65535) {
                throw new IOException("Invalid proxy port: " + proxyPort);
            }
            connection = (HttpURLConnection) url.openConnection(
                    new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)));
        }
        if (connection instanceof HttpsURLConnection) {
            AudioWindowsTrust.configure((HttpsURLConnection) connection);
        }
        return connection;
    }

    /** Read the available OpenAI-compatible model identifiers without consuming completion tokens. */
    public java.util.List<String> listModels() throws IOException {
        String suffix = "/chat/completions";
        String normalized = endpoint.trim().replaceAll("/+$", "");
        if (!normalized.endsWith(suffix)) {
            throw new IOException("The KIPITZ URL must end with /chat/completions to list models.");
        }
        URI address = URI.create(normalized.substring(0, normalized.length() - suffix.length()) + "/models");
        HttpURLConnection connection = openConnection(address);
        activeConnections.add(connection);
        try {
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("Accept", "application/json");
            if (apiKey != null && !apiKey.trim().isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new IOException("Model listing returned HTTP " + status + " (proxy " +
                        (routes == null ? "legacy" : routes.describe()) + ").");
            }
            try (InputStream input = connection.getInputStream()) {
                com.google.gson.JsonArray data = new JsonParser().parse(readText(input, 2_000_000))
                        .getAsJsonObject().getAsJsonArray("data");
                java.util.List<String> models = new java.util.ArrayList<String>();
                for (com.google.gson.JsonElement element : data) {
                    models.add(element.getAsJsonObject().get("id").getAsString());
                }
                java.util.Collections.sort(models);
                return models;
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Could not read the model list.", invalid);
        } finally {
            activeConnections.remove(connection);
            connection.disconnect();
        }
    }

    private static JsonObject message(String role, String content) {
        JsonObject result = new JsonObject();
        result.addProperty("role", role);
        result.addProperty("content", content);
        return result;
    }

    private static String readText(InputStream input, int limit) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] block = new byte[8192];
        int count;
        while ((count = input.read(block)) >= 0) {
            bytes.write(block, 0, count);
            if (bytes.size() > limit) {
                throw new IOException("KIPITZ response exceeds the configured size limit.");
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
