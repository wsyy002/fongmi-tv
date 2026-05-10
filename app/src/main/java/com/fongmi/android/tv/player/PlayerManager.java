package com.fongmi.android.tv.player;

import android.net.Uri;
import android.text.TextUtils;
import android.view.Surface;

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

    private static final int PLAYER_EXO = 0;
    private static final int PLAYER_IJK = 1;

    private final Runnable runnable;
    private final Callback callback;
    private ExoPlayerEngine exoEngine;
    private IjkPlayerEngine ijkEngine;
    private DanPlayer danPlayer;
    private VideoSize videoSize;
    private ParseJob parseJob;
    private PlaySpec spec;
    private Player player;
    private int activeEngine = PLAYER_EXO;
    private boolean forceIjk = false;
    private boolean forceExo = false;

    private boolean initTrack;
    private int retry;

    public PlayerManager(Callback callback) {
        this.runnable = () -> callback.onError(ResUtil.getString(R.string.error_play_timeout));
        this.exoEngine = new ExoPlayerEngine(PlayerEngine.HARD, listener);
        this.ijkEngine = new IjkPlayerEngine(PlayerEngine.HARD, null);
        this.player = exoEngine.getPlayer();
        this.callback = callback;
        this.activeEngine = PLAYER_EXO;
        // Connect IJK engine error callback to PlayerManager's callback
        this.ijkEngine.setCallback(new IjkPlayerEngine.Callback() {
            @Override
            public void onPrepare() {
                callback.onPrepare();
                App.removeCallbacks(runnable);
            }

            @Override
            public void onError(String msg) {
                callback.onError(msg);
            }
        });
    }

    private boolean isIjkMode() {
        return activeEngine == PLAYER_IJK;
    }

    /**
     * Switch active rendering engine.
     * ExoPlayer is ALWAYS kept alive for MediaSession compatibility.
     * In IJK mode, ExoPlayer is paused/stopped but not released.
     */
    public void setActiveEngine(int playerType) {
        if (this.activeEngine == playerType) return;
        this.activeEngine = playerType;
        switch (playerType) {
            case PLAYER_IJK:
                // Stop ExoPlayer but keep it alive for MediaSession
                if (player != null) {
                    player.stop();
                    player.clearMediaItems();
                }
                if (ijkEngine == null) ijkEngine = new IjkPlayerEngine(PlayerEngine.HARD, null);
                break;
            case PLAYER_EXO:
            default:
                if (ijkEngine != null) {
                    ijkEngine.stop();
                }
                if (player != null) {
                    player.setPlayWhenReady(true);
                }
                break;
        }
    }

    public void setIjkSurface(Surface surface) {
        if (ijkEngine != null && isIjkMode()) {
            ijkEngine.setSurface(surface);
        }
    }

    public void release() {
        stopParse();
        App.removeCallbacks(runnable);
        if (danPlayer != null) {
            danPlayer.release();
            danPlayer = null;
        }
        if (player != null) player.removeListener(listener);
        if (exoEngine != null) {
            exoEngine.release();
            exoEngine = null;
        }
        if (ijkEngine != null) {
            ijkEngine.release();
            ijkEngine = null;
        }
        player = null;
    }

    /**
     * Always return the ExoPlayer for MediaSession compatibility.
     * ExoPlayer is kept idle during IJK playback.
     */
    public Player getPlayer() {
        return player;
    }

    public Tracks getCurrentTracks() {
        if (!isIjkMode() && exoEngine != null) return exoEngine.getCurrentTracks();
        return Tracks.EMPTY;
    }

    public MediaItem getCurrentMediaItem() {
        return !isIjkMode() && player != null ? player.getCurrentMediaItem() : null;
    }

    public int getPlaybackState() {
        if (isIjkMode()) {
            IjkPlayerEngine ijk = ijkEngine;
            return ijk != null && ijk.isPrepared() && ijk.isPlaying() ? Player.STATE_READY :
                   ijk != null && ijk.isPrepared() && !ijk.isPlaying() ? Player.STATE_READY :
                   ijk != null && !ijk.isPrepared() ? Player.STATE_BUFFERING : Player.STATE_IDLE;
        }
        return player != null ? player.getPlaybackState() : Player.STATE_IDLE;
    }

    public boolean isPlaying() {
        if (isIjkMode()) {
            return ijkEngine != null && ijkEngine.isPlaying();
        }
        return player != null && player.isPlaying();
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
        return player != null ? player.getPlaybackParameters().speed : 1.0f;
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
        return isIjkMode() ? (ijkEngine != null && ijkEngine.isLive()) : (exoEngine != null && exoEngine.isLive());
    }

    public boolean isVod() {
        return isIjkMode() ? (ijkEngine == null || !ijkEngine.isLive()) : (exoEngine != null && exoEngine.isVod());
    }

    public boolean haveTrack(int type) {
        return isIjkMode() ? false : (exoEngine != null && exoEngine.haveTrack(type));
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
            return ijkEngine != null ? ijkEngine.getVideoWidth() : 0;
        }
        return videoSize == null ? 0 : videoSize.width;
    }

    public int getVideoHeight() {
        if (isIjkMode()) {
            return ijkEngine != null ? ijkEngine.getVideoHeight() : 0;
        }
        return videoSize == null ? 0 : videoSize.height;
    }

    public long getPosition() {
        if (isIjkMode()) {
            return ijkEngine != null ? ijkEngine.getCurrentPosition() : 0;
        }
        return player != null ? player.getCurrentPosition() : 0;
    }

    public String getSizeText() {
        return (getVideoWidth() == 0 && getVideoHeight() == 0) ? "" : getVideoWidth() + " x " + getVideoHeight();
    }

    public String getSpeedText() {
        return String.format(Locale.getDefault(), "%.2f", getSpeed());
    }

    public String getDecodeText() {
        return isIjkMode() && ijkEngine != null ? ijkEngine.getDecodeText() :
               exoEngine != null ? exoEngine.getDecodeText() : "";
    }

    public String getPositionTime(long delta) {
        long time = Math.max(0, Math.min(getPosition() + delta, Math.max(0, getDuration())));
        return Util.timeMs(time);
    }

    public long getDuration() {
        if (isIjkMode()) {
            return ijkEngine != null ? ijkEngine.getDuration() : 0;
        }
        return player != null ? player.getDuration() : 0;
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
        if (!isIjkMode() && exoEngine != null) exoEngine.setMetadata(data);
    }

    public void setDanmakuView(DanmakuView view) {
        danPlayer = new DanPlayer(view);
        if (player != null) danPlayer.attachPlayer(player);
    }

    public void setDanmakuSize(float size) {
        if (danPlayer != null) danPlayer.setTextSize(size);
    }

    public String setSpeed(float speed) {
        if (isIjkMode()) return getSpeedText();
        if (player == null || !player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return getSpeedText();
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
        if (!tracks.isEmpty() && !isIjkMode() && exoEngine != null) exoEngine.setTrack(tracks);
    }

    public void play() {
        if (isIjkMode()) {
            if (ijkEngine != null) ijkEngine.play();
        } else if (player != null) {
            player.play();
        }
    }

    public void pause() {
        if (isIjkMode()) {
            if (ijkEngine != null) ijkEngine.pause();
        } else if (player != null) {
            player.pause();
        }
    }

    public void stop() {
        if (danPlayer != null) danPlayer.stop();
        if (isIjkMode()) {
            if (ijkEngine != null) ijkEngine.stop();
        } else if (player != null) {
            player.stop();
        }
        stopParse();
    }

    public void setRepeatOne(boolean repeat) {
        if (!isIjkMode() && player != null) {
            player.setRepeatMode(repeat ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        }
    }

    public void seekTo(long time) {
        if (isIjkMode()) {
            if (ijkEngine != null) ijkEngine.seekTo(time);
        } else if (player != null) {
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
        if (!isIjkMode() && exoEngine != null) exoEngine.resetTrack();
    }

    public void toggleDecode() {
        if (isIjkMode()) {
            if (ijkEngine == null) return;
            ijkEngine.setDecode(ijkEngine.isHard() ? PlayerEngine.SOFT : PlayerEngine.HARD);
            rebuildIjkPlayer();
            setMediaItem();
        } else {
            if (exoEngine == null) return;
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
        // Always pass the ExoPlayer (kept alive for MediaSession)
        callback.onPlayerRebuild(player);
    }

    /** Set to force IJK mode regardless of URL detection. Used by LiveActivity. */
    public void setForceIjk(boolean force) {
        this.forceIjk = force;
        if (force) this.forceExo = false;
    }

    /** Set to force Exo mode regardless of URL detection. */
    public void setForceExo(boolean force) {
        this.forceExo = force;
        if (force) this.forceIjk = false;
    }

    public boolean isForcedIjk() {
        return forceIjk;
    }

    /**
     * Start playback with auto engine selection.
     * Priority: forceIjk > Setting.getLivePlayer() > URL detection > Setting.getPlayer()
     */
    public void start(PlaySpec spec, long timeout) {
        this.spec = spec;

        boolean shouldUseIjk = determineEngine(spec);

        if (shouldUseIjk && !isIjkMode()) {
            setActiveEngine(PLAYER_IJK);
            if (ijkEngine != null) ijkEngine.setLive(true);
        } else if (!shouldUseIjk && isIjkMode()) {
            setActiveEngine(PLAYER_EXO);
        }

        // Reset forced flags after use
        forceIjk = false;
        forceExo = false;

        setMediaItem(timeout);
    }

    /**
     * Determine which player engine to use for the given PlaySpec.
     * User setting: getPlayer()=0→System(Exo), 1→IJK, 2→Exo(default)
     * For live content, use getLivePlayer(): 0→System, 1→IJK(default), 2→Exo
     */
    private boolean determineEngine(PlaySpec spec) {
        // 1. Explicit force flags (set by LiveActivity/VodActivity)
        if (forceIjk) return true;
        if (forceExo) return false;

        // 2. Live content via URL detection
        boolean isLiveContent = spec != null && spec.getUrl() != null &&
                (spec.getUrl().contains(".m3u8") ||
                 spec.getUrl().contains(".flv") ||
                 spec.getUrl().contains("rtmp://") ||
                 spec.getUrl().contains("rtsp://") ||
                 spec.getUrl().contains("live") ||
                 spec.getUrl().contains("play") ||
                 spec.getUrl().startsWith("http://") && (
                     spec.getUrl().contains("tv") ||
                     spec.getUrl().contains("channel") ||
                     spec.getUrl().contains("stream")));

        if (isLiveContent) {
            // Live: 0=Exo, 1=IJK(default), 2=Exo
            return Setting.getLivePlayer() == 1;
        } else {
            // VOD: 0=System(Exo), 1=IJK, 2=Exo(default)
            return Setting.getPlayer() == 1;
        }
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata) {
        stopParse();
        // Apply pending engine switch (from forceIjk/forceExo set by LiveActivity)
        if (forceIjk && !isIjkMode()) {
            setActiveEngine(PLAYER_IJK);
            if (ijkEngine != null) ijkEngine.setLive(true);
        } else if (forceExo && isIjkMode()) {
            setActiveEngine(PLAYER_EXO);
        }
        forceIjk = false;
        forceExo = false;
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
            if (ijkEngine != null) {
                ijkEngine.setLive(!isVodDef());
                ijkEngine.start(spec.checkUa());
            }
        } else {
            if (exoEngine != null) {
                exoEngine.start(spec.checkUa());
            }
        }
        App.post(runnable, timeout);
        callback.onPrepare();
        initTrack = false;
    }

    /**
     * Determines if the current content should use ExoPlayer (VOD definition).
     * live_player: 0=global(use getPlayer), 1=IJK for live, 2=Exo for live
     */
    private boolean isVodDef() {
        int livePlayer = Setting.getLivePlayer();
        // Always return false in IJK mode, true in Exo mode
        return activeEngine == PLAYER_EXO;
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
            PlayerEngine.ErrorAction action = isIjkMode() && ijkEngine != null ? ijkEngine.handleError(e) : (exoEngine != null ? exoEngine.handleError(e) : PlayerEngine.ErrorAction.FATAL);
            if (action == PlayerEngine.ErrorAction.RECOVERED) return;
            if (++retry > 2) {
                callback.onError(isIjkMode() && ijkEngine != null ? ijkEngine.getErrorMessage(e) :
                        (exoEngine != null ? exoEngine.getErrorMessage(e) : e.getMessage()));
                return;
            }
            switch (action) {
                case DECODE:
                    toggleDecode();
                    break;
                case FATAL:
                    callback.onError(isIjkMode() && ijkEngine != null ? ijkEngine.getErrorMessage(e) :
                            (exoEngine != null ? exoEngine.getErrorMessage(e) : e.getMessage()));
                    break;
            }
        }
    };
}
