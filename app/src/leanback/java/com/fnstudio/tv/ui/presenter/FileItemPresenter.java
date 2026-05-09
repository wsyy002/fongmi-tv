package com.fnstudio.tv.ui.presenter;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.leanback.widget.Presenter;

import com.fnstudio.tv.smb.SmbManager.SmbFileInfo;
import com.fongmi.android.tv.R;

import java.text.DecimalFormat;
import java.util.Locale;

public class FileItemPresenter extends Presenter {

    private final Context context;

    public FileItemPresenter(Context context) {
        this.context = context;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_file, parent, false);
        return new FileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder viewHolder, Object item) {
        FileViewHolder holder = (FileViewHolder) viewHolder;
        SmbFileInfo info = (SmbFileInfo) item;
        holder.name.setText(info.getName());

        if (info.isDirectory()) {
            holder.icon.setImageResource(R.drawable.ic_home_folder);
            holder.size.setText("");
        } else {
            String name = info.getName().toLowerCase(Locale.US);
            if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi")
                    || name.endsWith(".mov") || name.endsWith(".wmv") || name.endsWith(".flv")
                    || name.endsWith(".ts") || name.endsWith(".m2ts")) {
                holder.icon.setImageResource(R.drawable.ic_home_video);
            } else if (name.endsWith(".iso")) {
                holder.icon.setImageResource(R.drawable.ic_home_iso);
            } else {
                holder.icon.setImageResource(R.drawable.ic_home_video);
            }
            holder.size.setText(formatFileSize(info.getSize()));
        }
    }

    @Override
    public void onUnbindViewHolder(ViewHolder viewHolder) {
    }

    private String formatFileSize(long size) {
        if (size <= 0) return "";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = (int) (Math.log10(size) / Math.log10(1024));
        double displaySize = size / Math.pow(1024, unitIndex);
        return new DecimalFormat("#.##").format(displaySize) + " " + units[unitIndex];
    }

    static class FileViewHolder extends ViewHolder {
        ImageView icon;
        TextView name;
        TextView size;

        FileViewHolder(View view) {
            super(view);
            icon = view.findViewById(R.id.file_icon);
            name = view.findViewById(R.id.file_name);
            size = view.findViewById(R.id.file_size);
        }
    }
}
