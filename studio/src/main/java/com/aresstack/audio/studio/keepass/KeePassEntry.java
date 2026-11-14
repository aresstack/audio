package com.aresstack.audio.studio.keepass;

public final class KeePassEntry {
    private final String title;
    private final String url;
    private final String password;

    public KeePassEntry(String title, String url, String password) {
        this.title = title == null ? "" : title;
        this.url = url == null ? "" : url;
        this.password = password == null ? "" : password;
    }

    public String title() { return title; }
    public String url() { return url; }
    public String password() { return password; }
    public boolean hasUrl() { return !url.isEmpty(); }

    @Override
    public String toString() {
        return title + (hasUrl() ? " (" + url + ")" : "");
    }
}
