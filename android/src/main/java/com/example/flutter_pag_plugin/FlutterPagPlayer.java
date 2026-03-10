package com.example.flutter_pag_plugin;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.animation.LinearInterpolator;

import org.libpag.PAGFile;
import org.libpag.PAGPlayer;
import org.libpag.PAGSurface;

import java.util.HashMap;

import io.flutter.plugin.common.MethodChannel;


public class FlutterPagPlayer extends PAGPlayer {

    private final ValueAnimator animator = ValueAnimator.ofFloat(0.0F, 1.0F);
    private boolean isRelease;
    private boolean surfaceAvailable;
    private long currentPlayTime = 0L;
    private double progress = 0;
    private double initProgress = 0;

    private MethodChannel channel;
    private long textureId;


    public FlutterPagPlayer() {
        super();
        surfaceAvailable = true;
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animatorUpdateListener);
        animator.addListener(animatorListenerAdapter);
    }

    public boolean isRelease() {
        return isRelease;
    }

    public boolean isSurfaceAvailable() {
        return valid();
    }

    public void init(PAGFile file, int repeatCount, double initProgress, MethodChannel channel, long textureId) {
        if (WorkThreadExecutor.multiThread) {
            synchronized (this) {
                setComposition(file);
            }
        } else {
            setComposition(file);
        }

        this.channel = channel;
        this.textureId = textureId;
        progress = initProgress;
        this.initProgress = initProgress;
        animator.setDuration(duration() / 1000L);
        if (repeatCount < 0) {
            repeatCount = 0;
        }
        animator.setRepeatCount(repeatCount - 1);
        setProgressValue(initProgress);
    }

    private boolean valid() {
        return surfaceAvailable && getSurface() != null;
    }


    public void setProgressValue(double value) {
        if (WorkThreadExecutor.multiThread) {
            synchronized (this) {
                this.progress = Math.max(0.0D, Math.min(value, 1.0D));
                this.currentPlayTime = (long) (progress * (double) this.animator.getDuration());
                this.animator.setCurrentPlayTime(currentPlayTime);
                setProgress(progress);
                flush();
            }
        } else {
            this.progress = Math.max(0.0D, Math.min(value, 1.0D));
            this.currentPlayTime = (long) (progress * (double) this.animator.getDuration());
            this.animator.setCurrentPlayTime(currentPlayTime);
            setProgress(progress);
            flush();
        }
    }

    public void start() {
        animator.start();
    }

    public void stop() {
        pause();
        setProgressValue(initProgress);
    }

    @Override
    public void setSurface(PAGSurface pagSurface) {
        if (WorkThreadExecutor.multiThread) {
            synchronized (this) {
                setSurfaceInternal(pagSurface);
            }
        } else {
            setSurfaceInternal(pagSurface);
        }
    }

    private void setSurfaceInternal(PAGSurface pagSurface) {
        PAGSurface oldSurface = getSurface();
        super.setSurface(pagSurface);
        surfaceAvailable = pagSurface != null;
        if (oldSurface != null && oldSurface != pagSurface) {
            oldSurface.release();
        }
    }

    public void onSurfaceCleanup() {
        if (WorkThreadExecutor.multiThread) {
            synchronized (this) {
                releaseCurrentSurface();
                surfaceAvailable = false;
            }
        } else {
            releaseCurrentSurface();
            surfaceAvailable = false;
        }
    }

    private void releaseCurrentSurface() {
        PAGSurface currentSurface = getSurface();
        super.setSurface(null);
        if (currentSurface != null) {
            currentSurface.release();
        }
    }


    public void updateBufferSize() {
        updateBufferSize(true);
    }

    public void updateBufferSize(boolean clearSurface) {
        if (WorkThreadExecutor.multiThread) {
            synchronized (this) {
                updateBufferSizeInternal(clearSurface);
            }
        } else {
            updateBufferSizeInternal(clearSurface);
        }
    }

    private void updateBufferSizeInternal(boolean clearSurface) {
        PAGSurface surface = getSurface();
        if (surface != null) {
            surface.updateSize();
            if (clearSurface) {
                surface.clearAll();
            }
        }
    }

    public void clear() {
        if (WorkThreadExecutor.multiThread) {
            synchronized (this) {
                setComposition(null);
                if (valid()) {
                    getSurface().freeCache();
                    getSurface().clearAll();
                }
            }
        } else {
            setComposition(null);
            if (valid()) {
                getSurface().freeCache();
                getSurface().clearAll();
            }
        }
    }

    public void cancel() {
        animator.cancel();
    }

    public void pause() {
        animator.pause();
    }

    @Override
    public void release() {
        super.release();
        animator.cancel();
        animator.removeAllUpdateListeners();
        animator.removeAllListeners();
        surfaceAvailable = false;
        //此处如果放入子线程处理，会打印gl的错误日志，挪到主线程
        if (WorkThreadExecutor.multiThread) {
            synchronized (this) {
                if (getSurface() != null) getSurface().release();

            }
        } else {
            if (getSurface() != null) getSurface().release();

        }
        isRelease = true;
    }

    @Override
    public boolean flush() {
        if (isRelease) {
            return false;
        }
        WorkThreadExecutor.getInstance().post(() -> {
            if (WorkThreadExecutor.multiThread) {
                synchronized (this) {
                    if (!valid()) {
                        return;
                    }
                    FlutterPagPlayer.super.flush();
                }
            } else {
                if (!valid()) {
                    return;
                }
                FlutterPagPlayer.super.flush();
            }

        });
        return valid();

//        return super.flush();
    }

    // 更新PAG渲染
    private final ValueAnimator.AnimatorUpdateListener animatorUpdateListener = new ValueAnimator.AnimatorUpdateListener() {

        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            progress = (double) (Float) animation.getAnimatedValue();
            currentPlayTime = (long) (progress * (double) animator.getDuration());
            if (WorkThreadExecutor.multiThread) {
                synchronized (FlutterPagPlayer.this) {
                    setProgress(progress);
                    flush();
                }
            } else {
                setProgress(progress);
                flush();
            }
        }
    };

    // 动画状态监听
    private final AnimatorListenerAdapter animatorListenerAdapter = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationStart(Animator animator) {
            super.onAnimationStart(animator);
            notifyEvent(FlutterPagPlugin._eventStart);
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            super.onAnimationEnd(animation);
            // Align with iOS platform, avoid triggering this method when stopping
            int repeatCount = ((ValueAnimator) animation).getRepeatCount();
            if (repeatCount >= 0 && (animation.getDuration() > 0) &&
                    (currentPlayTime / animation.getDuration() > repeatCount)) {
                notifyEvent(FlutterPagPlugin._eventEnd);
            }
        }

        @Override
        public void onAnimationCancel(Animator animator) {
            super.onAnimationCancel(animator);
            notifyEvent(FlutterPagPlugin._eventCancel);
        }

        @Override
        public void onAnimationRepeat(Animator animator) {
            super.onAnimationRepeat(animator);
            notifyEvent(FlutterPagPlugin._eventRepeat);
        }
    };

    void notifyEvent(String event) {
        final HashMap<String, Object> arguments = new HashMap<>();
        arguments.put(FlutterPagPlugin._argumentTextureId, textureId);
        arguments.put(FlutterPagPlugin._argumentEvent, event);
        channel.invokeMethod(FlutterPagPlugin._playCallback, arguments);
    }
}
