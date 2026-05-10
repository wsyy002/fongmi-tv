package com.fongmi.android.tv.player.engine;

import android.graphics.SurfaceTexture;
import android.text.TextUtils;
import android.view.Surface;

import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;

import com.fongmi.android.tv.bean.Track;

import java.util.List;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import tv.danmaku.ijk.media.player.IjkTimedText;
import tv.danmaku.ijk.media.player.MediaInfo;

public class IjkPlayerEngine implements PlayerEngine {

    private IjkMediaPlayer player;
    private PlaySpec spec;
    private int decode;
    private boolean isPlaying;
    private boolean isPrepared;
    private boolean live;
    private Surface surface;
    private Callback callback;
    private String errorMsg;
    private int videoWidth;
    private int videoHeight;
    private long currentPosition;
    private long duration;

    public IjkPlayerEngine(int decode, Player.Listener exoListener) {
        this.decode = decode;
        initPlayer();
    }

    private void initPlayer() {
        if (player != null) {
            player.resetListeners();
            try { player.release(); } catch (Exception ignored) { }
        }
        try {
            player = new IjkMediaPlayer();
        } catch (UnsatisfiedLinkError e) {
            errorMsg = "IJK native library load failed: " + e.getMessage();
            player = null;
            return;
        } catch (Exception e) {
            errorMsg = "IJK init failed: " + e.getMessage();
            player = null;
            return;
        }
        isPrepared = false;
        isPlaying = false;
        errorMsg = null;
        videoWidth = 0;
        videoHeight = 0;
        currentPosition = 0;
        duration = 0;

        // ijkplayer optimizations for live streaming
        try {
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", decode == HARD ? 1 : 0);
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1);
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1);
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 1);
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1);
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1);
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "http-detect-range-support", 0);
            // Live-friendly options
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", live ? 0 : 1);
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "infbuf", live ? 1 : 0);
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", live ? 0 : (long) 15 * 1024 * 1024);
            // Network
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_transport", "tcp");
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtmp_flush_audio", 1);
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1);
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_at_eof", 1);
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_streamed", 1);
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_delay_max", 5);
            // Codec
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48);
            // Log level
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "enable-log", 0);
        } catch (Exception ignored) {
            // Some options may fail on certain builds
        }

        player.setListener(new IMediaPlayer.Listener() {
            @Override
            public void onPrepared(IMediaPlayer mp) {
                isPrepared = true;
                duration = mp.getDuration();
                videoWidth = mp.getVideoWidth();
                videoHeight = mp.getVideoHeight();
                if (surface != null) {
                    player.setSurface(surface);
                }
                player.start();
                isPlaying = true;
                if (callback != null) callback.onPrepare();
            }

            @Override
            public void onCompletion(IMediaPlayer mp) {
                isPlaying = false;
                if (callback != null) {
                    callback.onStateChanged(Player.STATE_ENDED);
                }
            }

            @Override
            public boolean onError(IMediaPlayer mp, int what, int extra) {
                isPlaying = false;
                isPrepared = false;
                switch (what) {
                    case IjkMediaPlayer.MEDIA_ERROR_IO:
                    case IjkMediaPlayer.MEDIA_ERROR_MALFORMED:
                        errorMsg = "播放地址错误";
                        break;
                    case IjkMediaPlayer.MEDIA_ERROR_TIMED_OUT:
                        errorMsg = "播放超时";
                        break;
                    case IjkMediaPlayer.MEDIA_ERROR_UNKNOWN:
                    default:
                        errorMsg = "IJK Error: what=" + what + " extra=" + extra;
                        break;
                }
                if (callback != null) callback.onError(errorMsg);
                return true;
            }

            @Override
            public boolean onInfo(IMediaPlayer mp, int what, int extra) {
                switch (what) {
                    case IjkMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START:
                        isPlaying = true;
                        if (callback != null) callback.onStateChanged(Player.STATE_READY);
                        break;
                    case IjkMediaPlayer.MEDIA_INFO_BUFFERING_START:
                        if (callback != null) callback.onStateChanged(Player.STATE_BUFFERING);
                        break;
                    case IjkMediaPlayer.MEDIA_INFO_BUFFERING_END:
                        if (isPlaying && callback != null) callback.onStateChanged(Player.STATE_READY);
                        break;
                    case IjkMediaPlayer.MEDIA_INFO_VIDEO_ROTATION_CHANGED:
                        break;
                }
                return true;
            }

            @Override
            public void onVideoSizeChanged(IMediaPlayer mp, int width, int height, int sarNum, int sarDen) {
                videoWidth = width;
                videoHeight = height;
            }

            @Override
            public void onTimedText(IMediaPlayer mp, IjkTimedText text) {
            }
        });
    }

    public void setCallback(Callback cb) {
        this.callback = cb;
    }

    public void setSurface(Surface surface) {
        this.surface = surface;
        if (player != null) {
            try {
                player.setSurface(surface);
            } catch (Exception ignored) {
            }
        }
    }

    public void setSurfaceTexture(SurfaceTexture surfaceTexture) {
        setSurface(new Surface(surfaceTexture));
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
            try {
                player.resetListeners();
                player.stop();
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        if (surface != null && surface.isValid()) {
            surface.release();
        }
        surface = null;
        isPrepared = false;
        isPlaying = false;
        callback = null;
    }

    @Override
    public Player rebuild(Player.Listener listener) {
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
        initPlayer();
    }

    @Override
    public boolean isHard() {
        return decode == HARD;
    }

    @Override
    public String getDecodeText() {
        return decode == HARD ? "Hard" : "Soft";
    }

    @Override
    public void start(PlaySpec spec) {
        this.spec = spec;
        startInternal();
    }

    private void startInternal() {
        if (player == null || spec == null || TextUtils.isEmpty(spec.getUrl())) return;
        try {
            isPlaying = false;
            isPrepared = false;
            errorMsg = null;
            player.reset();

            // Apply user-agent if provided
            if (spec.getHeaders() != null && spec.getHeaders().containsKey("User-Agent")) {
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", spec.getHeaders().get("User-Agent"));
            }

            // Re-apply live settings
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", live ? 0 : 1);
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "infbuf", live ? 1 : 0);
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", live ? 0 : (long) 15 * 1024 * 1024);

            // Set surface if already available
            if (surface != null && surface.isValid()) {
                player.setSurface(surface);
            }

            player.setDataSource(spec.getUrl());
            player.prepareAsync();
        } catch (Exception e) {
            errorMsg = e.getMessage();
            if (callback != null) callback.onError(errorMsg);
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
        // IjkMediaPlayer doesn't expose a direct track switching API
    }

    @Override
    public void resetTrack() {
        // Not supported
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
        return !TextUtils.isEmpty(errorMsg) ? errorMsg : "播放地址错误";
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        return ErrorAction.FATAL;
    }

    // Player control methods (to be called from PlayerManager)

    public boolean isPlaying() {
        return isPlaying;
    }

    public long getCurrentPosition() {
        try {
            return player != null && isPrepared ? player.getCurrentPosition() : currentPosition;
        } catch (Exception e) {
            return currentPosition;
        }
    }

    public long getDuration() {
        return duration;
    }

    public int getVideoWidth() {
        return videoWidth;
    }

    public int getVideoHeight() {
        return videoHeight;
    }

    public void play() {
        if (player != null && isPrepared) {
            player.start();
            isPlaying = true;
        }
    }

    public void pause() {
        if (player != null && isPrepared) {
            player.pause();
            isPlaying = false;
        }
    }

    public void stop() {
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            isPlaying = false;
            isPrepared = false;
        }
    }

    public void seekTo(long msec) {
        if (player != null && isPrepared) {
            try {
                player.seekTo(msec);
            } catch (Exception ignored) {
            }
        }
    }

    public void resetPlayer() {
        if (player != null) {
            try {
                player.reset();
            } catch (Exception ignored) {
            }
        }
        isPrepared = false;
        isPlaying = false;
    }

    public boolean isPrepared() {
        return isPrepared;
    }

    public interface Callback {
        default void onPrepare() {}
        default void onError(String msg) {}
        default void onStateChanged(int state) {}
    }
}
