package com.aresstack.audio.instruments.network;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Trust certificates accepted by either Java or the Windows root store, as in 3d4j. */
public final class AudioWindowsTrust {
    private static final SSLSocketFactory TRUST = build();

    private AudioWindowsTrust() {
    }

    public static void configure(HttpsURLConnection connection) {
        if (TRUST != null) {
            connection.setSSLSocketFactory(TRUST);
        }
    }

    private static SSLSocketFactory build() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            return null;
        }
        try {
            List<X509TrustManager> managers = new ArrayList<X509TrustManager>();
            managers.add(trustManager(null));
            try {
                KeyStore roots = KeyStore.getInstance("Windows-ROOT");
                roots.load(null, null);
                managers.add(trustManager(roots));
            } catch (GeneralSecurityException unavailable) {
                // Keep the built-in Java certificate authorities if Windows ROOT is unavailable.
            } catch (java.io.IOException unavailable) {
                // Keep the built-in Java certificate authorities if Windows ROOT is unavailable.
            }
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, new TrustManager[]{new CompositeTrustManager(managers)}, null);
            return tls.getSocketFactory();
        } catch (GeneralSecurityException unavailable) {
            return null;
        }
    }

    private static X509TrustManager trustManager(KeyStore store) throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager) {
                return (X509TrustManager) manager;
            }
        }
        throw new GeneralSecurityException("No trusted certificate managers available.");
    }

    private static final class CompositeTrustManager implements X509TrustManager {
        private final List<X509TrustManager> delegates;

        private CompositeTrustManager(List<X509TrustManager> delegates) {
            this.delegates = delegates;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] certificates, String authentication)
                throws CertificateException {
            CertificateException last = null;
            for (X509TrustManager manager : delegates) {
                try {
                    manager.checkServerTrusted(certificates, authentication);
                    return;
                } catch (CertificateException rejected) {
                    last = rejected;
                }
            }
            throw last != null ? last : new CertificateException("No trusted CA accepted this server.");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] certificates, String authentication)
                throws CertificateException {
            throw new CertificateException("Client trust is not supported.");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> all = new ArrayList<X509Certificate>();
            for (X509TrustManager manager : delegates) {
                all.addAll(Arrays.asList(manager.getAcceptedIssuers()));
            }
            return all.toArray(new X509Certificate[all.size()]);
        }
    }
}
