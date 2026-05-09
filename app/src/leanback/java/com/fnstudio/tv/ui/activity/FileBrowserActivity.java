package com.fnstudio.tv.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.VerticalGridView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.ActivityFileBrowserBinding;
import com.fnstudio.tv.smb.SmbManager;
import com.fnstudio.tv.smb.SmbManager.SmbFileInfo;
import com.fnstudio.tv.smb.SmbManager.SmbServer;
import com.fnstudio.tv.ui.presenter.FileItemPresenter;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.ResUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FileBrowserActivity extends BaseActivity {

    private static final int TAB_LOCAL = 0;
    private static final int TAB_NAS = 1;

    private ActivityFileBrowserBinding mBinding;
    private ArrayObjectAdapter mAdapter;
    private FileItemPresenter mPresenter;

    private int currentTab = TAB_LOCAL;
    private File currentDir;
    private SmbManager smbManager;
    private boolean smbConnected;
    private String smbCurrentPath;
    private String smbShareName; // store current share name for display

    public static void start(Activity activity) {
        Intent intent = new Intent(activity, FileBrowserActivity.class);
        activity.startActivity(intent);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityFileBrowserBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mPresenter = new FileItemPresenter(this);
        mAdapter = new ArrayObjectAdapter(mPresenter);
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter));
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
        mBinding.recycler.setFocusScrollStrategy(VerticalGridView.FOCUS_SCROLL_ALIGNED);
        mBinding.recycler.setWindowAlignment(VerticalGridView.WINDOW_ALIGN_NO_EDGE);

        mBinding.title.setText(R.string.home_file);
        smbManager = new SmbManager();

        switchToLocal();
    }

    private void switchToLocal() {
        currentTab = TAB_LOCAL;
        mBinding.title.setText(getString(R.string.home_file) + " - Local");
        loadStorageVolumes();
    }

    private void switchToNas() {
        currentTab = TAB_NAS;
        mBinding.title.setText(getString(R.string.home_file) + " - NAS");
        List<SmbServer> servers = SmbManager.getServers(this);
        if (servers.isEmpty()) {
            showSmbSettingsDialog();
        } else {
            connectToSmb(servers.get(0));
        }
    }

    private void loadStorageVolumes() {
        List<SmbFileInfo> items = new ArrayList<>();

        File primaryStorage = Environment.getExternalStorageDirectory();
        if (primaryStorage != null) {
            SmbFileInfo info = new SmbFileInfo();
            info.setName("Internal Storage");
            info.setDirectory(true);
            info.setPath(primaryStorage.getAbsolutePath());
            items.add(info);
        }

        File[] externalDirs = getExternalFilesDirs(null);
        for (File dir : externalDirs) {
            if (dir != null) {
                String path = dir.getAbsolutePath();
                int idx = path.indexOf("/Android");
                if (idx > 0) {
                    String root = path.substring(0, idx);
                    SmbFileInfo info = new SmbFileInfo();
                    info.setName(root.substring(root.lastIndexOf('/') + 1));
                    info.setDirectory(true);
                    info.setPath(root);
                    if (!items.contains(info)) items.add(info);
                }
            }
        }

        // Also check /storage/ for mounted volumes
        File storage = new File("/storage/");
        if (storage.exists() && storage.isDirectory()) {
            File[] volumes = storage.listFiles();
            if (volumes != null) {
                for (File vol : volumes) {
                    if (vol.isDirectory() && vol.canRead()) {
                        SmbFileInfo info = new SmbFileInfo();
                        info.setName(vol.getName());
                        info.setDirectory(true);
                        info.setPath(vol.getAbsolutePath());
                        if (!items.contains(info)) items.add(info);
                    }
                }
            }
        }

        mAdapter.clear();
        mAdapter.addAll(0, items);
        mBinding.empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        mBinding.path.setText("/storage");
        currentDir = null;
    }

    private void listLocalDirectory(File dir) {
        if (dir == null || !dir.exists()) return;
        currentDir = dir;

        File[] files = dir.listFiles();
        List<SmbFileInfo> items = new ArrayList<>();

        // Parent directory entry
        if (dir.getParentFile() != null) {
            SmbFileInfo parentInfo = new SmbFileInfo();
            parentInfo.setName("..");
            parentInfo.setDirectory(true);
            parentInfo.setPath(dir.getParent());
            items.add(parentInfo);
        }

        if (files != null) {
            Arrays.sort(files, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            for (File file : files) {
                SmbFileInfo info = new SmbFileInfo();
                info.setName(file.getName());
                info.setDirectory(file.isDirectory());
                info.setSize(file.length());
                info.setPath(file.getAbsolutePath());
                items.add(info);
            }
        }

        mAdapter.clear();
        mAdapter.addAll(0, items);
        mBinding.empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        mBinding.path.setText(dir.getAbsolutePath());
    }

    private void showSmbSettingsDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_smb_settings, null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        TextView host = dialogView.findViewById(R.id.smb_host);
        TextView share = dialogView.findViewById(R.id.smb_share);
        TextView user = dialogView.findViewById(R.id.smb_user);
        TextView pass = dialogView.findViewById(R.id.smb_pass);
        TextView status = dialogView.findViewById(R.id.smb_status);
        View connect = dialogView.findViewById(R.id.smb_connect);

        List<SmbServer> servers = SmbManager.getServers(this);
        if (!servers.isEmpty()) {
            SmbServer srv = servers.get(0);
            host.setText(srv.getHost());
            share.setText(srv.getShareName());
            user.setText(srv.getUsername());
            pass.setText(srv.getPassword());
        } else {
            host.setText("192.168.101.28");
            share.setText("Video");
        }

        connect.setOnClickListener(v -> {
            String h = host.getText().toString().trim();
            String s = share.getText().toString().trim();
            String u = user.getText().toString().trim();
            String p = pass.getText().toString().trim();
            if (TextUtils.isEmpty(h) || TextUtils.isEmpty(s)) {
                status.setText("Host and Share are required");
                return;
            }
            SmbServer server = new SmbServer(h, 445, s, u, p);
            SmbManager.addServer(this, server);
            status.setText("Connecting...");
            dialog.dismiss();
            connectToSmb(server);
        });

        dialog.show();
    }

    private void connectToSmb(SmbServer server) {
        new Thread(() -> {
            try {
                smbManager.connectWithShare(server.getHost(), server.getPort(),
                        server.getUsername(), server.getPassword(), server.getShareName());
                smbConnected = true;
                smbShareName = server.getShareName();
                smbCurrentPath = "";
                runOnUiThread(() -> {
                    mBinding.title.setText("NAS - " + smbShareName);
                    listSmbDirectory("");
                });
            } catch (Exception e) {
                e.printStackTrace();
                smbConnected = false;
                runOnUiThread(() -> {
                    mBinding.empty.setText("Failed to connect: " + e.getMessage());
                    mBinding.empty.setVisibility(View.VISIBLE);
                    showSmbSettingsDialog();
                });
            }
        }).start();
    }

    private void listSmbDirectory(String path) {
        if (!smbConnected || smbManager == null) return;
        new Thread(() -> {
            try {
                List<SmbFileInfo> files = smbManager.listFiles(path);
                smbCurrentPath = path;
                runOnUiThread(() -> {
                    mAdapter.clear();
                    // Parent directory entry
                    if (!TextUtils.isEmpty(path)) {
                        SmbFileInfo parentInfo = new SmbFileInfo();
                        parentInfo.setName("..");
                        parentInfo.setDirectory(true);
                        int idx = path.lastIndexOf('/');
                        parentInfo.setPath(idx > 0 ? path.substring(0, idx) : "");
                        mAdapter.add(parentInfo);
                    }
                    mAdapter.addAll(0, files);
                    mBinding.empty.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
                    mBinding.path.setText("/" + (TextUtils.isEmpty(path) ? smbShareName : path));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    mBinding.empty.setText("Error: " + e.getMessage());
                    mBinding.empty.setVisibility(View.VISIBLE);
                });
            }
        }).start();
    }

    private SmbServer getCurrentSmbServer() {
        List<SmbServer> servers = SmbManager.getServers(this);
        return servers.isEmpty() ? null : servers.get(0);
    }

    private void playFile(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (path.toLowerCase().endsWith(".iso")) {
            // Try to extract largest m2ts from ISO Blu-ray
            String m2tsPath = extractIsoMainM2ts(path);
            if (m2tsPath != null) {
                VideoActivity.file(this, m2tsPath);
            } else {
                // Fallback: try playing ISO directly
                VideoActivity.start(this, "file://" + path, "file://" + path, name);
            }
        } else {
            VideoActivity.file(this, path);
        }
    }

    private String extractIsoMainM2ts(String isoPath) {
        try {
            RandomAccessFile raf = new RandomAccessFile(isoPath, "r");
            // Find BDMV/STREAM/ directory using ISO 9660 parsing
            // Volume Descriptor at sector 16 (2048 bytes per sector for DVD/BD)
            byte[] sector = new byte[2048];
            long largestM2ts = 0;
            String largestName = null;
            
            // Read volume descriptor set at LBA 16
            raf.seek(16 * 2048L);
            raf.readFully(sector);
            
            // Volume Descriptor: type at offset 0, identifier "CD001" at offset 1
            if (sector[0] != 1 || sector[1] != 'C' || sector[2] != 'D' || sector[3] != '0' || sector[4] != '0' || sector[5] != '1') {
                // Try UDF Anchor Volume Descriptor Pointer at LBA 256
                raf.seek(256 * 2048L);
                raf.readFully(sector);
                // UDF identifier "BEA01" at offset 0
                if ((sector[0] != (byte)0x42 || sector[1] != (byte)0x45 || 
                     sector[2] != (byte)0x41 || sector[3] != (byte)0x30 || sector[4] != (byte)0x31) &&
                    (sector[0] != (byte)0x4E || sector[1] != (byte)0x53 || /* NSR02/03 */
                     sector[2] != (byte)0x52)) {
                    raf.close();
                    return null;
                }
                // Read Logical Volume Descriptor from AVDP
                // Simplified: scan entire ISO for files with .m2ts extension
                raf.close();
                return scanIsoForM2ts(isoPath);
            }
            
            // ISO 9660: Read root directory from volume descriptor
            // Root directory record at offset 156
            // This is complex; fallback to scanning
            raf.close();
            return scanIsoForM2ts(isoPath);
        } catch (Exception e) {
            return null;
        }
    }
    
    private String scanIsoForM2ts(String isoPath) {
        try {
            RandomAccessFile raf = new RandomAccessFile(isoPath, "r");
            long fileSize = raf.length();
            long largestSize = 0;
            long largestOffset = -1;
            String largestName = null;
            
            // Scan for M2TS headers (0x000001BA or 0x4741 for MPEG-TS)
            // M2TS files have 0x47 sync byte every 192 bytes
            byte[] buf = new byte[4096];
            long offset = 0;
            int m2tsCount = 0;
            
            while (offset < fileSize) {
                int read = raf.read(buf);
                if (read <= 0) break;
                
                for (int i = 0; i < read - 192; i++) {
                    // Check for M2TS/MTS: 0x47 sync byte at position 0, 192, 384...
                    if ((buf[i] & 0xFF) == 0x47 && (buf[i + 192] & 0xFF) == 0x47) {
                        m2tsCount++;
                        long segmentSize = findM2tsSegmentSize(raf, isoPath, offset + i);
                        if (segmentSize > largestSize) {
                            largestSize = segmentSize;
                            largestOffset = offset + i;
                        }
                        raf.seek(offset + i + Math.max(segmentSize, 192));
                        offset = offset + i + Math.max(segmentSize, 192);
                        i += Math.max(segmentSize, 192);
                    }
                }
                offset += read;
            }
            
            raf.close();
            
            if (m2tsCount > 0 && largestOffset >= 0) {
                String outPath = isoPath + ".main.m2ts";
                // Copy largest segment to temp file
                extractSegment(isoPath, outPath, largestOffset, largestSize);
                return outPath;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
    
    private long findM2tsSegmentSize(RandomAccessFile raf, String isoPath, long startOffset) {
        try {
            long endOffset = raf.length();
            raf.seek(startOffset);
            byte[] headerBuf = new byte[4];
            long pos = startOffset;
            int consecutiveSync = 0;
            
            while (pos < endOffset) {
                raf.readFully(headerBuf);
                if ((headerBuf[0] & 0xFF) == 0x47) {
                    consecutiveSync++;
                    if (consecutiveSync >= 100) {
                        // Found many sync bytes, seems valid
                        break;
                    }
                    pos += 192;
                    raf.seek(pos);
                } else {
                    // End of segment
                    return pos - startOffset;
                }
            }
            return pos - startOffset;
        } catch (Exception e) {
            return 0;
        }
    }
    
    private void extractSegment(String isoPath, String outPath, long offset, long size) {
        try {
            File outFile = new File(outPath);
            if (outFile.exists() && outFile.length() == size) return;
            
            RandomAccessFile raf = new RandomAccessFile(isoPath, "r");
            FileOutputStream fos = new FileOutputStream(outPath);
            raf.seek(offset);
            byte[] buffer = new byte[8192];
            long remaining = size;
            
            while (remaining > 0) {
                int read = raf.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read <= 0) break;
                fos.write(buffer, 0, read);
                remaining -= read;
            }
            
            fos.close();
            raf.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void playSmbFile(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        String smbUrl = "smb://" + path;
        VideoActivity.start(this, smbUrl, smbUrl, name);
    }

    private void openItem(Object item) {
        if (item instanceof SmbFileInfo) {
            SmbFileInfo info = (SmbFileInfo) item;
            if (currentTab == TAB_LOCAL) {
                if (info.getName().equals("..") && currentDir != null && currentDir.getParentFile() != null) {
                    if (currentDir.getAbsolutePath().equals(Environment.getExternalStorageDirectory().getAbsolutePath())
                            || currentDir.getAbsolutePath().equals("/storage")) {
                        loadStorageVolumes();
                    } else {
                        listLocalDirectory(currentDir.getParentFile());
                    }
                } else if (info.isDirectory()) {
                    listLocalDirectory(new File(info.getPath()));
                } else {
                    playFile(info.getPath());
                }
            } else if (currentTab == TAB_NAS) {
                if (info.getName().equals("..")) {
                    listSmbDirectory(info.getPath());
                } else if (info.isDirectory()) {
                    listSmbDirectory(info.getPath());
                } else {
                    playSmbFile(info.getPath());
                }
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (currentTab == TAB_NAS) {
                        switchToLocal();
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (currentTab == TAB_LOCAL) {
                        switchToNas();
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (mBinding.recycler.getSelectedPosition() >= 0
                            && mBinding.recycler.getSelectedPosition() < mAdapter.size()) {
                        Object item = mAdapter.get(mBinding.recycler.getSelectedPosition());
                        openItem(item);
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_BACK:
                    if (currentTab == TAB_LOCAL && currentDir != null) {
                        File parent = currentDir.getParentFile();
                        if (parent != null) {
                            listLocalDirectory(parent);
                            return true;
                        }
                    }
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDestroy() {
        if (smbManager != null) {
            new Thread(() -> {
                try { smbManager.disconnect(); } catch (Exception e) { /* ignore */ }
            }).start();
        }
        super.onDestroy();
    }
}
