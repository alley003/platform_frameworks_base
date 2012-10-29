/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.policy.impl.keyguard;

import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;

import com.android.internal.policy.impl.keyguard.KeyguardActivityLauncher.CameraWidgetInfo;

public class CameraWidgetFrame extends KeyguardWidgetFrame {
    private static final String TAG = CameraWidgetFrame.class.getSimpleName();
    private static final boolean DEBUG = KeyguardHostView.DEBUG;
    private static final int WIDGET_ANIMATION_DURATION = 250;

    interface Callbacks {
        void onLaunchingCamera();
        void onCameraLaunched();
    }

    private final Handler mHandler = new Handler();
    private final KeyguardActivityLauncher mActivityLauncher;
    private final Callbacks mCallbacks;

    private View mWidgetView;
    private long mLaunchCameraStart;
    private boolean mRendered;

    private final Runnable mLaunchCameraRunnable = new Runnable() {
        @Override
        public void run() {
            mLaunchCameraStart = SystemClock.uptimeMillis();
            mActivityLauncher.launchCamera();
        }};

    private final Runnable mRenderRunnable = new Runnable() {
        @Override
        public void run() {
            render();
        }};

    private CameraWidgetFrame(Context context, Callbacks callbacks,
            KeyguardActivityLauncher activityLauncher) {
        super(context);

        mCallbacks = callbacks;
        mActivityLauncher = activityLauncher;
    }

    public static CameraWidgetFrame create(Context context, Callbacks callbacks,
            KeyguardActivityLauncher launcher) {
        if (context == null || callbacks == null || launcher == null)
            return null;

        CameraWidgetInfo widgetInfo = launcher.getCameraWidgetInfo();
        if (widgetInfo == null)
            return null;
        View widgetView = widgetInfo.layoutId > 0 ?
                inflateWidgetView(context, widgetInfo) :
                inflateGenericWidgetView(context);
        if (widgetView == null)
            return null;

        ImageView preview = new ImageView(context);
        preview.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        preview.setScaleType(ScaleType.FIT_CENTER);
        CameraWidgetFrame cameraWidgetFrame = new CameraWidgetFrame(context, callbacks, launcher);
        cameraWidgetFrame.addView(preview);
        cameraWidgetFrame.mWidgetView = widgetView;
        return cameraWidgetFrame;
    }

    private static View inflateWidgetView(Context context, CameraWidgetInfo widgetInfo) {
        View widgetView = null;
        Exception exception = null;
        try {
            Context cameraContext = context.createPackageContext(
                    widgetInfo.contextPackage, Context.CONTEXT_RESTRICTED);
            LayoutInflater cameraInflater = (LayoutInflater)
                    cameraContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            cameraInflater = cameraInflater.cloneInContext(cameraContext);
            widgetView = cameraInflater.inflate(widgetInfo.layoutId, null, false);
        } catch (NameNotFoundException e) {
            exception = e;
        } catch (RuntimeException e) {
            exception = e;
        }
        if (exception != null) {
            Log.w(TAG, "Error creating camera widget view", exception);
        }
        return widgetView;
    }

    private static View inflateGenericWidgetView(Context context) {
        ImageView iv = new ImageView(context);
        iv.setImageResource(com.android.internal.R.drawable.ic_lockscreen_camera);
        iv.setScaleType(ScaleType.CENTER);
        iv.setBackgroundColor(Color.argb(127, 0, 0, 0));
        return iv;
    }

    public void render() {
        if (mRendered) return;

        try {
            int width = getRootView().getWidth();
            int height = getRootView().getHeight();
            if (DEBUG) Log.d(TAG, String.format("render [%sx%s] %s",
                    width, height, Integer.toHexString(hashCode())));
            if (width == 0 || height == 0) {
                return;
            }
            Bitmap offscreen = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(offscreen);
            mWidgetView.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            mWidgetView.layout(0, 0, width, height);
            mWidgetView.draw(c);
            ((ImageView)getChildAt(0)).setImageBitmap(offscreen);
            mRendered = true;
        } catch (Throwable t) {
            Log.w(TAG, "Error rendering camera widget", t);
            removeAllViews();
            View genericView = inflateGenericWidgetView(mContext);
            addView(genericView);
        }
    }

    private void transitionToCamera() {
        int startWidth = getChildAt(0).getWidth();
        int startHeight = getChildAt(0).getHeight();

        int finishWidth = getRootView().getWidth();
        int finishHeight = getRootView().getHeight();

        float scaleX = (float) finishWidth / startWidth;
        float scaleY = (float) finishHeight / startHeight;

        float scale = Math.max(scaleX, scaleY);
        animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(WIDGET_ANIMATION_DURATION)
            .withEndAction(mLaunchCameraRunnable)
            .start();
        mCallbacks.onLaunchingCamera();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);

        if (!hasWindowFocus) {
            if (mLaunchCameraStart > 0) {
                long launchTime = SystemClock.uptimeMillis() - mLaunchCameraStart;
                if (DEBUG) Log.d(TAG, String.format("Camera took %sms to launch", launchTime));
                mLaunchCameraStart = 0;
                onCameraLaunched();
            }
        }
    }

    @Override
    public void onActive(boolean isActive) {
        if (isActive) {
            mHandler.post(new Runnable(){
                @Override
                public void run() {
                    transitionToCamera();
                }});
        } else {
            reset();
        }
    }

    private void onCameraLaunched() {
        reset();
        mCallbacks.onCameraLaunched();
    }

    private void reset() {
        animate().cancel();
        setScaleX(1);
        setScaleY(1);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mHandler.post(mRenderRunnable);
    }
}
