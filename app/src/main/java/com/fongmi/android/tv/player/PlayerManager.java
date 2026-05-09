package com.fongmi.android.tv.player;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Setting;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.impl.ParseCallback;
import com.fongmi.android.tv.player.danmaku.DanPlayer;
import com.fongmi.android.tv.player.engine.ExoPlayerEngine;
import com.fongmi.android.tv.player.engine.IjkPlayerEngine;
import com.fongmi.android.tv.player.engine.PlaySpec;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.common.net.HttpHeaders;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import master.flame.danmaku.ui.widget.DanmakuView;

public class PlayerManager implements ParseCallback {

    private final Runnable runnable;
    private final Callback callback;
    private ExoPlayerEngine exoEngine;
    private IjkPlayerEngine ijkEngine;
    private DanPlayer danPlayer;
    private VideoSize videoSize;
    private ParseJob parseJob;
    private PlaySpec spec;
    private Player player;
    private boolean useIjk;

    private boolean initTrack;
    private int retry;

    public PlayerManager(Callback callback) {
        this.runnable = () -> callback.onError(ResUtil.getString(R.string.error_play_timeout));
        this.exoEngine = new ExoPlayerEngine(PlayerEngine.HARD, listener);
        this.ijkEngine = new IjkPlayerEngine(PlayerEngine.HARD, null);
        this.player = exoEngine.getPlayer();
        this.callback = callback;
        this.useIjk = false;
    }

    private boolean isIjkMode() {
        return useIjk;
    }

    public void setIjkMode(boolean ijk) {
        if (this.useIjk == ijk) return;
        this.useIjk = ijk;
        if (ijk) {
            if (player != null) player.removeListener(listener);
            if (exoEngine != null) exoEngine.release();
            player = null;
            if (ijkEngine == null) ijkEngine = new IjkPlayerEngine(PlayerEngine.HARD, null);
        } else {
            if (ijkEngine != null) ijkEngine.release();
            if (exoEngine == null) exoEngine = new ExoPlayerEngine(PlayerEngine.HARD, listener);
            player = exoEngine.getPlayer();
        }
    }

    public void release() {
        stopParse();
        App.removeCallbacks(runnable);
        if (danPlayer != null) {
            danPlayer.release();
            danPlayer = null;
        }
        if (isIjkMode()) {
            if (ijkEngine != null) {
                ijkEngine.release();
                ijkEngine = null;
            }
        } else {
            if (player != null) player.removeListener(listener);
            if (exoEngine != null) {
                exoEngine.release();
                exoEngine = null;
            }
            player = null;
        }
    }

    public Player getPlayer() {
        return isIjkMode() ? null : player;
    }

    public Tracks getCurrentTracks() {
        return isIjkMode() ? null : exoEngine.getCurrentTracks();
    }

    public MediaItem getCurrentMediaItem() {
        return isIjkMode() ? null : player.getCurrentMediaItem();
    }

    public int getPlaybackState() {
        return isIjkMode() ? Player.STATE_READY : player.getPlaybackState();
    }

    public boolean isPlaying() {
        if (isIjkMode()) {
            tv.danmaku.ijk.media.player.IjkMediaPlayer ijk = ijkEngine.getIjkPlayer();
            return ijk != null && ijk.isPlaying();
        }
        return player.isPlaying();
    }

    public String getUrl() {
        return spec != null ? spec.getUrl() : null;
    }

    public String getKey() {
        return spec != null ? spec.getKey() : null;
    }

    public List<Danmaku> getDanmakus() {
        return spec != null ? spec.getDanmakus() : null;
    }

    public Map<String, String> getHeaders() {
        return spec == null || spec.getHeaders() == null ? new HashMap<>() : spec.getHeaders();
    }

    public float getSpeed() {
        return isIjkMode() ? 1.0f : player.getPlaybackParameters().speed;
    }

    public boolean isEmpty() {
        return spec == null || TextUtils.isEmpty(spec.getUrl());
    }

    public boolean isPortrait() {
        return getVideoHeight() > getVideoWidth();
    }

    public boolean isLandscape() {
        return getVideoWidth() > getVideoHeight();
    }

    public boolean isLive() {
        return isIjkMode() ? ijkEngine.isLive() : exoEngine.isLive();
    }

    public boolean isVod() {
        return isIjkMode() ? ijkEngine.isVod() : exoEngine.isVod();
    }

    public boolean haveTrack(int type) {
        return isIjkMode() ? false : exoEngine.haveTrack(type);
    }

    public boolean haveDanmaku() {
        return getDanmakus() != null && getDanmakus().stream().anyMatch(Danmaku::isSelected);
    }

    public boolean canSetOpening(long position, long duration) {
        return position > 0 && duration > 0 && position <= Constant.getOpEdLimit(duration);
    }

    public boolean canSetEnding(long position, long duration) {
        return position > 0 && duration > 0 && duration - position <= Constant.getOpEdLimit(duration);
    }

    public int getVideoWidth() {
        if (isIjkMode()) {
            tv.danmaku.ijk.media.player.IjkMediaPlayer ijk = ijkEngine.getIjkPlayer();
            return ijk == null ? 0 : ijk.getVideoWidth();
        }
        return videoSize == null ? 0 : videoSize.width;
    }

    public int getVideoHeight() {
        if (isIjkMode()) {
            tv.danmaku.ijk.media.player.IjkMediaPlayer ijk = ijkEngine.getIjkPlayer();
            return ijk == null ? 0 : ijk.getVideoHeight();
        }
        return videoSize == null ? 0 : videoSize.height;
    }

    public long getPosition() {
        if (isIjkMode()) {
            tv.danmaku.ijk.media.player.IjkMediaPlayer ijk = ijkEngine.getIjkPlayer();
            return ijk == null ? 0 : ijk.getCurrentPosition();
        }
        return player.getCurrentPosition();
    }

    public String getSizeText() {
        return (getVideoWidth() == 0 && getVideoHeight() == 0) ? "" : getVideoWidth() + " x " + getVideoHeight();
    }

    public String getSpeedText() {
        return String.format(Locale.getDefault(), "%.2f", getSpeed());
    }

    public String getDecodeText() {
        return isIjkMode() ? ijkEngine.getDecodeText() : exoEngine.getDecodeText();
    }

    public String getPositionTime(long delta) {
        long time = Math.max(0, Math.min(getPosition() + delta, Math.max(0, getDuration())));
        return Util.timeMs(time);
    }

    public long getDuration() {
        if (isIjkMode()) {
            tv.danmaku.ijk.media.player.IjkMediaPlayer ijk = ijkEngine.getIjkPlayer();
            return ijk == null ? 0 : ijk.getDuration();
        }
        return player.getDuration();
    }

    public String getDurationTime() {
        return Util.timeMs(Math.max(0, getDuration()));
    }

    public void setSub(Sub sub) {
        if (spec != null) spec.setSub(sub);
        if (!isIjkMode()) setMediaItem();
    }

    public void setFormat(String format) {
        if (spec != null) spec.setFormat(format);
        if (!isIjkMode()) setMediaItem();
    }

    public static MediaMetadata buildMetadata(String title, String artist, String artUri) {
        Uri artwork = TextUtils.isEmpty(artUri) ? null : Uri.parse(artUri);
        return new MediaMetadata.Builder().setTitle(title).setArtist(artist).setArtworkUri(artwork).build();
    }

    public void setMetadata(MediaMetadata data) {
        if (spec != null) spec.setMetadata(data);
        if (!isIjkMode()) exoEngine.setMetadata(data);
    }

    public void setDanmakuView(DanmakuView view) {
        danPlayer = new DanPlayer(view);
        danPlayer.attachPlayer(player);
    }

    public void setDanmakuSize(float size) {
        danPlayer.setTextSize(size);
    }

    public String setSpeed(float speed) {
        if (isIjkMode()) return getSpeedText();
        if (!player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return getSpeedText();
        player.setPlaybackParameters(player.getPlaybackParameters().withSpeed(speed));
        return getSpeedText();
    }

    public String addSpeed() {
        float speed = getSpeed();
        float addon = speed >= 2 ? 1f : 0.25f;
        speed = speed >= 5 ? 0.25f : Math.min(speed + addon, 5.0f);
        return setSpeed(speed);
    }

    public String addSpeed(float value) {
        return setSpeed(Math.min(getSpeed() + value, 5));
    }

    public String subSpeed(float value) {
        return setSpeed(Math.max(getSpeed() - value, 0.25f));
    }

    public String toggleSpeed() {
        return setSpeed(getSpeed() == 1 ? Setting.getSpeed() : 1);
    }

    public void setTrack(List<Track> tracks) {
        if (!tracks.isEmpty() && !isIjkMode()) exoEngine.setTrack(tracks);
    }

    public void play() {
        if (isIjkMode()) {
            tv.danmaku.ijk.media.player.IjkMediaPlayer ijk = ijkEngine.getIjkPlayer();
            if (ijk != null) ijk.start();
        } else {
            player.play();
        }
    }

    public void pause() {
        if (isIjkMode()) {
            tv.danmaku.ijk.media.player.IjkMediaPlayer ijk = ijkEngine.getIjkPlayer();
            if (ijk != null) ijk.pause();
        } else {
            player.pause();
        }
    }

    public void stop() {
        if (danPlayer != null) danPlayer.stop();
        if (isIjkMode()) {
            tv.danmaku.ijk.media.player.IjkMediaPlayer ijk = ijkEngine.getIjkPlayer();
            if (ijk != null) ijk.stop();
        } else {
            player.stop();
        }
        stopParse();
    }

    public void setRepeatOne(boolean repeat) {
        if (isIjkMode()) return;
        player.setRepeatMode(repeat ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
    }

    public void seekTo(long time) {
        if (isIjkMode()) {
            tv.danmaku.ijk.media.player.IjkMediaPlayer ijk = ijkEngine.getIjkPlayer();
            if (ijk != null) ijk.seekTo(time);
        } else {
            player.seekTo(time);
        }
    }

    public void reset() {
        App.removeCallbacks(runnable);
        retry = 0;
    }

    public void clear() {
        spec = null;
    }

    public void resetTrack() {
        if (!isIjkMode()) exoEngine.resetTrack();
    }

    public void toggleDecode() {
        if (isIjkMode()) {
            ijkEngine.setDecode(ijkEngine.isHard() ? PlayerEngine.SOFT : PlayerEngine.HARD);
            rebuildIjkPlayer();
            setMediaItem();
        } else {
            exoEngine.setDecode(exoEngine.isHard() ? PlayerEngine.SOFT : PlayerEngine.HARD);
            rebuildPlayer();
            setMediaItem();
        }
    }

    private void rebuildPlayer() {
        player = exoEngine.rebuild(listener);
        if (danPlayer != null) danPlayer.attachPlayer(player);
        callback.onPlayerRebuild(player);
    }

    private void rebuildIjkPlayer() {
        ijkEngine.rebuild(null);
        callback.onPlayerRebuild(null);
    }

    public void start(PlaySpec spec, long timeout) {
        this.spec = spec;
        setMediaItem(timeout);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata) {
        stopParse();
        spec = PlaySpec.fromParse(result, key, metadata);
        parseJob = ParseJob.create(this).start(result, useParse);
    }

    private void stopParse() {
        if (parseJob != null) parseJob.stop();
        parseJob = null;
    }

    public void setMediaItem() {
        setMediaItem(Constant.TIMEOUT_PLAY);
    }

    private void setMediaItem(long timeout) {
        if (spec == null || spec.getUrl() == null) return;
        setDanmakus(spec.getDanmakus());
        if (isIjkMode()) {
            ijkEngine.setLive(!isVodDef());
            ijkEngine.start(spec.checkUa());
        } else {
            exoEngine.start(spec.checkUa());
        }
        App.post(runnable, timeout);
        callback.onPrepare();
        initTrack = false;
    }

    private boolean isVodDef() {
        return Setting.getPlayer() != 1;
    }

    public void startBrowse(PlaySpec spec) {
        reset();
        clear();
        stopParse();
        start(spec, Constant.TIMEOUT_PLAY);
    }

    private void setDanmakus(List<Danmaku> items) {
        if (danPlayer != null) setDanmaku(items == null || items.isEmpty() ? Danmaku.empty() : items.get(0));
    }

    public void setDanmaku(Danmaku item) {
        if (spec != null) spec.setDanmaku(item);
        if (danPlayer != null) danPlayer.setDanmaku(item);
    }

    public IjkPlayerEngine getIjkEngine() {
        return ijkEngine;
    }

    public ExoPlayerEngine getExoEngine() {
        return exoEngine;
    }

    @Override
    public void onParseSuccess(Map<String, String> headers, String url, String from) {
        if (!TextUtils.isEmpty(from)) Notify.show(ResUtil.getString(R.string.parse_from, from));
        if (headers != null) headers.remove(HttpHeaders.RANGE);
        if (spec != null) spec.setHeaders(headers);
        if (spec != null) spec.setUrl(url);
        setMediaItem();
    }

    @Override
    public void onParseError() {
        callback.onError(ResUtil.getString(R.string.error_play_parse));
    }

    public interface Callback {

        void onPrepare();

        void onTracksChanged();

        void onTitlesChanged();

        void onError(String msg);

        void onPlayerRebuild(Player newPlayer);
    }

    private final Player.Listener listener = new Player.Listener() {

        @Override
        public void onPlaybackStateChanged(int state) {
            if (state != Player.STATE_IDLE) App.removeCallbacks(runnable);
        }

        @Override
        public void onVideoSizeChanged(@NonNull VideoSize size) {
            videoSize = size;
        }

        @Override
        public void onTracksChanged(@NonNull Tracks tracks) {
            if (tracks.isEmpty() || initTrack) return;
            setTrack(Track.find(getKey()));
            callback.onTracksChanged();
            initTrack = true;
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException e) {
            PlayerEngine.ErrorAction action = isIjkMode() ? ijkEngine.handleError(e) : exoEngine.handleError(e);
            if (action == PlayerEngine.ErrorAction.RECOVERED) return;
            if (++retry > 2) {
                callback.onError(isIjkMode() ? ijkEngine.getErrorMessage(e) : exoEngine.getErrorMessage(e));
                return;
            }
            switch (action) {
                case DECODE:
                    toggleDecode();
                    break;
                case FATAL:
                    callback.onError(isIjkMode() ? ijkEngine.getErrorMessage(e) : exoEngine.getErrorMessage(e));
                    break;
            }
        }
    };
}
