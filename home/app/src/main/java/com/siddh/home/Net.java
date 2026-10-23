package com.siddh.home;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Blocking HTTPS helpers for Feed. Call off the UI thread. */
final class Net {
    private Net() {}

    static String get(String url, String bearer) throws IOException {
        HttpURLConnection c = open(url, bearer);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    /** Downloads to a temp file, then renames, so a cut connection never leaves half an image. */
    static boolean download(String url, File dst) {
        File tmp = new File(dst.getPath() + ".part");
        HttpURLConnection c = null;
        try {
            c = open(url, null);
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                copy(in, out);
            }
            return tmp.renameTo(dst);
        } catch (IOException e) {
            tmp.delete();
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String bearer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "HomeLauncher/0.3 (personal Fire TV launcher; Android)");
        c.setRequestProperty("Accept", "application/json, image/*");
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        if (c.getResponseCode() / 100 != 2) {
            int code = c.getResponseCode();
            c.disconnect();
            throw new IOException("HTTP " + code + " for " + url.replaceAll("api_key=[^&]+", "api_key=…"));
        }
        return c;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
    }
}
