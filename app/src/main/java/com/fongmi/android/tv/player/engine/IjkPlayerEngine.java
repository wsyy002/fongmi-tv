package com.fongmi.android.tv.player.engine;

import android.net.Uri;

import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.List;

import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import tv.danmaku.ijk.media.player.MediaInfo;

public class IjkPlayerEngine implements PlayerEngine {

    private IjkMediaPlayer player;
    private PlaySpec spec;
    private int decode;
    private boolean live;

    public IjkPlayerEngine(int decode, Listener listener) {
        this.player = new IjkMediaPlayer();
        this.decode = decode;
        initPlayer();
    }

    private void initPlayer() {
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", decode == HARD ? 1 : 0);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 1);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "http-detect-range-support", 0);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_transport", "tcp");
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtmp_flush_audio", 1);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_at_eof", 1);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_streamed", 1);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_delay_max", 5);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48);
    }

    @Override
    public Player getPlayer() {
        return null;
    }

    public IjkMediaPlayer getIjkPlayer() {
        return player;
    }

    @Override
    public void release() {
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
    }

    @Override
    public Player rebuild(Listener listener) {
        release();
        player = new IjkMediaPlayer();
        initPlayer();
        return null;
    }

    @Override
    public int getDecode() {
        return decode;
    }

    @Override
    public void setDecode(int decode) {
        this.decode = decode;
        rebuild(null);
    }

    @Override
    public boolean isHard() {
        return decode == HARD;
    }

    @Override
    public String getDecodeText() {
        return ResUtil.getStringArray(R.array.select_decode)[decode];
    }

    @Override
    public void start(PlaySpec spec) {
        this.spec = spec;
        startInternal();
    }

    private void startInternal() {
        if (player == null || spec == null || spec.getUrl() == null) return;
        try {
            player.reset();
            player.setDataSource(spec.getUrl());
            player.prepareAsync();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void setMetadata(MediaMetadata data) {
        // Not supported in IJK mode
    }

    @Override
    public boolean isLive() {
        return live;
    }

    public void setLive(boolean live) {
        this.live = live;
    }

    @Override
    public boolean isVod() {
        return !live;
    }

    @Override
    public void setTrack(List<Track> tracks) {
        // Not directly supported through IjkMediaPlayer track API
    }

    @Override
    public void resetTrack() {
        // Not directly supported
    }

    @Override
    public boolean haveTrack(int type) {
        return false;
    }

    @Override
    public Tracks getCurrentTracks() {
        return null;
    }

    @Override
    public String getErrorMessage(PlaybackException e) {
        return ResUtil.getString(R.string.error_play_url);
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        return ErrorAction.FATAL;
    }

    public void setSurface(Object surface) {
        if (player != null) {
            player.setSurface((android.view.Surface) surface);
        }
    }
}
