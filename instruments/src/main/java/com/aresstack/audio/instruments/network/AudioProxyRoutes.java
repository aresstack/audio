package com.aresstack.audio.instruments.network;

import com.aresstack.winproxy.ProxyConfiguration;
import com.aresstack.winproxy.ProxyMode;
import com.aresstack.winproxy.ProxyResult;
import com.aresstack.winproxy.WindowsProxyResolver;

import java.io.IOException;
import java.io.Reader;
import java.net.Authenticator;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Reuse the corporate proxy strategy of 3d4j: PAC/WPAD via win-proxy-java,
 * Windows trust and KeePass-configured BASIC proxy credentials when configured.
 * Never silently switch to DIRECT when PAC resolution fails.
 */
public final class AudioProxyRoutes {
    private final String modeName;
    private final ProxyMode mode;
    private final WindowsProxyResolver resolver;
    private final long timeoutMillis;
    private final String configurationProblem;
    private final List<String> excludedHosts = new ArrayList<String>();
    private final Map<String, Proxy> known = new ConcurrentHashMap<String, Proxy>();
    private final ExecutorService workers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "audio-proxy-resolver");
        thread.setDaemon(true);
        return thread;
    });

    public AudioProxyRoutes(Properties audioSettings) {
        Properties own = normalize(audioSettings);
        Properties game = read(Paths.get(System.getProperty("user.home"), ".3d4j", "generator.properties"));
        String appData = System.getenv("APPDATA");
        Properties enterprise = read(Paths.get(
                appData == null || appData.trim().isEmpty() ? System.getProperty("user.home") : appData,
                ".enterprise-ai-client", "enterprise-ai-client.properties"));

        String raw = first("network.proxy.mode", own, game, enterprise);
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        String pacUrl = first("network.proxy.pacUrl", own, game, enterprise);
        String selected = raw == null ? (windows ? "PAC_URL_POWERSHELL" : "DISABLED")
                : raw.toUpperCase(Locale.ROOT).replace('-', '_');

        if ("AUTO".equals(selected)) {
            selected = pacUrl == null ? "PAC_URL_POWERSHELL" : "PAC_URL_MANUAL";
        } else if ("NONE".equals(selected) || "DIRECT".equals(selected)) {
            selected = "DISABLED";
        } else if ("MANUAL".equals(selected)) {
            selected = "MANUAL_PROXY";
        } else if ("SYSTEM".equals(selected)) {
            selected = "WINDOWS_STATIC_PROXY";
        }
        modeName = selected;

        ProxyMode chosen = null;
        String problem = null;
        try {
            chosen = ProxyMode.valueOf(selected);
        } catch (IllegalArgumentException invalid) {
            problem = "Unknown proxy mode: " + selected;
        }
        mode = chosen;
        long configuredTimeout = 20000;
        String setting = first("network.proxy.resolveTimeoutMillis", own, game, enterprise);
        if (setting != null) {
            try {
                configuredTimeout = Math.max(1000, Long.parseLong(setting));
            } catch (NumberFormatException invalid) {
                problem = "Invalid proxy resolution timeout: " + setting;
            }
        }
        timeoutMillis = configuredTimeout;

        String exclusions = first("network.proxy.nonProxyHosts", own, game, enterprise);
        if (exclusions != null) {
            for (String host : exclusions.split("[,;|\\s]+")) {
                if (!host.trim().isEmpty()) {
                    excludedHosts.add(host.trim().toLowerCase(Locale.ROOT));
                }
            }
        }

        WindowsProxyResolver created = null;
        if (chosen != null && chosen != ProxyMode.DISABLED) {
            try {
                ProxyConfiguration.Builder builder = ProxyConfiguration.builder()
                        .mode(chosen).testUrl("http://127.0.0.1/");
                if (pacUrl != null) {
                    builder.pacUrl(pacUrl);
                }
                String host = first("network.proxy.host", own, game, enterprise);
                if (host != null) {
                    builder.manualProxyHost(host);
                }
                String port = first("network.proxy.port", own, game, enterprise);
                if (port != null) {
                    builder.manualProxyPort(Integer.parseInt(port));
                }
                String discovery = first("network.proxy.pacDiscoveryScript", own, game, enterprise);
                if (discovery != null) {
                    builder.pacUrlDiscoveryScript(discovery);
                }
                ProxyConfiguration configuration = builder.build();
                List<String> errors = configuration.validationProblems();
                if (errors.isEmpty()) {
                    created = new WindowsProxyResolver(configuration);
                } else {
                    problem = join(errors);
                }
            } catch (RuntimeException invalid) {
                problem = invalid.getMessage();
            }
        }
        resolver = created;
        configurationProblem = problem;
    }

    public String describe() {
        return modeName;
    }

    public Proxy proxyFor(URI target) throws IOException {
        String host = target.getHost();
        if (host == null) {
            throw new IOException("Proxy destination has no host: " + target);
        }
        if (mode == ProxyMode.DISABLED || isLoopback(host) || isExcluded(host)) {
            return Proxy.NO_PROXY;
        }
        if (resolver == null) {
            throw new IOException("No proxy route for " + host + " (" + modeName
                    + "): " + configurationProblem);
        }
        String url = resolutionUrl(target);
        Proxy saved = known.get(url);
        if (saved != null) {
            return saved;
        }
        final String resolution = url;
        ProxyResult result;
        Future<ProxyResult> request = workers.submit(new Callable<ProxyResult>() {
            @Override
            public ProxyResult call() {
                return resolver.resolve(resolution);
            }
        });
        try {
            result = request.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException failed) {
            request.cancel(true);
            throw new IOException("Timed out resolving corporate proxy (" + modeName + ").", failed);
        } catch (InterruptedException interrupted) {
            request.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted resolving proxy.", interrupted);
        } catch (ExecutionException failed) {
            throw new IOException("Proxy resolution failed (" + modeName + "): " + failed.getCause(), failed);
        }
        Proxy routed;
        if (result != null && result.isDirect()) {
            routed = Proxy.NO_PROXY;
        } else if (result != null && result.isProxy()) {
            routed = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(
                    result.getHost(), result.getPort()));
        } else {
            throw new IOException("Proxy resolution failed (" + modeName + "): "
                    + (result == null ? "No result" : result.getReason() + " " + result.getDetail()));
        }
        known.put(url, routed);
        return routed;
    }

    /** Enable the KeePass-backed HTTP BASIC login only when explicitly configured. */
    public void enableBasicAuthentication(Properties userSettings,
                                          final Supplier<PasswordAuthentication> supplier) {
        Properties own = normalize(userSettings);
        String method = first("network.proxy.auth.mode", own,
                read(Paths.get(System.getProperty("user.home"), ".3d4j", "generator.properties")),
                enterpriseSettings());
        if (!"BASIC".equalsIgnoreCase(method)) {
            return;
        }
        if (System.getProperty("jdk.http.auth.tunneling.disabledSchemes") == null) {
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        }
        Authenticator.setDefault(new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() != RequestorType.PROXY) {
                    return null;
                }
                return supplier.get();
            }
        });
    }

    public static String basicCredentialReference(Properties settings) {
        Properties own = normalize(settings);
        return first("network.proxy.auth.credentialRef", own,
                read(Paths.get(System.getProperty("user.home"), ".3d4j", "generator.properties")),
                enterpriseSettings());
    }

    public static String effectiveMode(Properties settings) {
        return new AudioProxyRoutes(settings).describe();
    }

    private boolean isExcluded(String host) {
        String name = host.toLowerCase(Locale.ROOT);
        for (String pattern : excludedHosts) {
            if (pattern.startsWith("*") ? name.endsWith(pattern.substring(1))
                    : name.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLoopback(String host) {
        String name = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(name) || name.endsWith(".localhost")) {
            return true;
        }
        if (name.matches("[0-9.]+") || name.contains(":")) {
            try {
                return InetAddress.getByName(name.replace("[", "").replace("]", ""))
                        .isLoopbackAddress();
            } catch (IOException failed) {
                return false;
            }
        }
        return false;
    }

    private static String resolutionUrl(URI target) {
        String port = target.getPort() < 0 ? "" : ":" + target.getPort();
        if ("https".equalsIgnoreCase(target.getScheme())) {
            return "https://" + target.getHost() + port + "/";
        }
        String path = target.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        return target.getScheme() + "://" + target.getHost() + port + path;
    }

    private static Properties normalize(Properties settings) {
        Properties out = new Properties();
        out.putAll(settings);
        putIfPresent(out, "network.proxy.mode", settings.getProperty("proxyMode"));
        putIfPresent(out, "network.proxy.host", settings.getProperty("proxyHost"));
        putIfPresent(out, "network.proxy.port", settings.getProperty("proxyPort"));
        putIfPresent(out, "network.proxy.pacUrl", settings.getProperty("proxyPacUrl"));
        putIfPresent(out, "network.proxy.nonProxyHosts", settings.getProperty("proxyNonProxyHosts"));
        putIfPresent(out, "network.proxy.auth.mode", settings.getProperty("proxyAuthMode"));
        putIfPresent(out, "network.proxy.auth.credentialRef", settings.getProperty("proxyCredentialRef"));
        return out;
    }

    private static void putIfPresent(Properties target, String property, String value) {
        if (value != null && !value.trim().isEmpty()) {
            target.setProperty(property, value.trim());
        }
    }

    private static String first(String name, Properties... sources) {
        for (Properties source : sources) {
            String value = source.getProperty(name);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private static Properties enterpriseSettings() {
        String appData = System.getenv("APPDATA");
        return read(Paths.get(
                appData == null || appData.trim().isEmpty() ? System.getProperty("user.home") : appData,
                ".enterprise-ai-client", "enterprise-ai-client.properties"));
    }

    private static Properties read(Path file) {
        Properties result = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader source = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                result.load(source);
            } catch (IOException invalid) {
                // Treat unreadable foreign settings as absent.
            }
        }
        return result;
    }

    private static String join(List<String> items) {
        StringBuilder text = new StringBuilder();
        for (String item : items) {
            if (text.length() > 0) {
                text.append("; ");
            }
            text.append(item);
        }
        return text.toString();
    }
}
