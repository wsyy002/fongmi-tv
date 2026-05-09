package com.fnstudio.tv.smb;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

public class SmbManager {

    private static final String TAG = SmbManager.class.getSimpleName();
    private static final String PREFS_NAME = "smb_credentials";
    private static final String KEY_SERVERS = "smb_servers";

    private SMBClient client;
    private Connection connection;
    private Session session;
    private DiskShare share;

    public SmbManager() {
        this.client = new SMBClient();
    }

    public void connect(String host, int port, String username, String password) throws IOException {
        connection = (port > 0 && port != 445) ? client.connect(host, port) : client.connect(host);
        AuthenticationContext auth = TextUtils.isEmpty(username)
                ? AuthenticationContext.anonymous()
                : new AuthenticationContext(username, password.toCharArray(), null);
        session = connection.authenticate(auth);
    }

    public void connectWithShare(String host, int port, String username, String password, String shareName) throws IOException {
        connect(host, port, username, password);
        share = (DiskShare) session.connectShare(shareName);
    }

    public List<SmbFileInfo> listFiles(String path) {
        List<SmbFileInfo> result = new ArrayList<>();
        try {
            String dir = TextUtils.isEmpty(path) ? "" : path;
            for (FileIdBothDirectoryInformation item : share.list(dir)) {
                String name = item.getFileName();
                if (".".equals(name) || "..".equals(name)) continue;
                SmbFileInfo info = new SmbFileInfo();
                info.setName(name);
                boolean isDir = java.util.Objects.equals(item.getFileAttributes(), null) ? false : (item.getFileAttributes() & 0x10) != 0;
                info.setDirectory(isDir);
                Long eof = item.getEndOfFile();
                info.setSize(eof != null ? eof : 0);
                info.setPath((dir.isEmpty() ? "" : dir.endsWith("/") ? dir : dir + "/") + name);
                result.add(info);
            }
        } catch (Exception e) {
            Log.e(TAG, "listFiles error: " + path, e);
        }
        Collections.sort(result, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        return result;
    }

    public void disconnect() {
        try { if (share != null) share.close(); } catch (Exception ignored) {}
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (connection != null) connection.close(); } catch (Exception ignored) {}
        try { if (client != null) client.close(); } catch (Exception ignored) {}
    }

    public boolean isConnected() {
        return connection != null && connection.isConnected();
    }

    // Credential management
    public static List<SmbServer> getServers(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_SERVERS, "[]");
        try {
            return SmbServer.arrayFrom(json);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static void saveServers(Context context, List<SmbServer> servers) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_SERVERS, SmbServer.toJson(servers)).apply();
    }

    public static void addServer(Context context, SmbServer server) {
        List<SmbServer> servers = getServers(context);
        servers.remove(server);
        servers.add(0, server);
        saveServers(context, servers);
    }

    public static void removeServer(Context context, SmbServer server) {
        List<SmbServer> servers = getServers(context);
        servers.remove(server);
        saveServers(context, servers);
    }

    public static class SmbFileInfo {
        private String name;
        private boolean directory;
        private long size;
        private String path;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public boolean isDirectory() { return directory; }
        public void setDirectory(boolean directory) { this.directory = directory; }
        public long getSize() { return size; }
        public void setSize(long size) { this.size = size; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
    }

    public static class SmbServer {
        private String host;
        private int port;
        private String shareName;
        private String username;
        private String password;
        private String displayName;

        public SmbServer() { this.port = 445; }

        public SmbServer(String host, int port, String shareName, String username, String password) {
            this.host = host;
            this.port = port;
            this.shareName = shareName;
            this.username = username;
            this.password = password;
            this.displayName = host + "/" + shareName;
        }

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getShareName() { return shareName; }
        public void setShareName(String shareName) { this.shareName = shareName; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }

        public static List<SmbServer> arrayFrom(String json) {
            try {
                com.google.gson.reflect.TypeToken<List<SmbServer>> token =
                    new com.google.gson.reflect.TypeToken<List<SmbServer>>() {};
                return com.fongmi.android.tv.App.gson().fromJson(json, token.getType());
            } catch (Exception e) { return new ArrayList<>(); }
        }

        public static String toJson(List<SmbServer> servers) {
            return com.fongmi.android.tv.App.gson().toJson(servers);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof SmbServer)) return false;
            SmbServer other = (SmbServer) obj;
            return host != null && host.equals(other.host)
                && shareName != null && shareName.equals(other.shareName);
        }
    }
}
