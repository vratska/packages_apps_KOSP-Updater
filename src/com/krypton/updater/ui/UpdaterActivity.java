/*
 * Copyright (C) 2021 AOSP-Krypton Project
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

package com.krypton.updater.ui;

import static android.graphics.Color.TRANSPARENT;
import static android.view.HapticFeedbackConstants.KEYBOARD_PRESS;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.SharedPreferences;
import android.graphics.drawable.Animatable2.AnimationCallback;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.preference.PreferenceManager;
import android.view.GestureDetector;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.GestureDetectorCompat;

import com.krypton.updater.R;
import com.krypton.updater.services.NetworkService;
import com.krypton.updater.Utils;

public class UpdaterActivity extends AppCompatActivity {

    private Handler handler;
    private GestureDetectorCompat detector;
    private LinearLayout downloadProgressLayout;
    private AnimatedVectorDrawable animatedDrawable;
    private ProgressBar downloadProgressBar;
    private TextView downloadProgressText;
    private TextView downloadStatus;
    private TextView viewLatestBuild;
    private TextView latestBuildVersion;
    private TextView latestBuildTimestamp;
    private TextView latestBuildName;
    private TextView latestBuildMd5sum;
    private ImageView refreshIcon;
    private Button downloadButton;
    private Button pauseButton;
    private Button cancelButton;
    private Button updateButton;
    private SharedPreferences sharedPrefs;
    private boolean hasUpdatedBuildInfo = true;
    private boolean downloading = false;
    private boolean downloadPaused = false;
    private boolean downloadFinished = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handler = new UIHandler(Looper.getMainLooper());
        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        setAppTheme(sharedPrefs.getInt(Utils.THEME_KEY, 2));
        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setTitle(R.string.updater_app_title);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.updater_activity);
        detector = new GestureDetectorCompat(this, new GestureListener());
        setCurrentBuildInfo();
        setWidgets();
        Intent intent = new Intent(this, NetworkService.class);
        intent.putExtra("Handler", new Messenger(handler));
        startService(intent);
        updateBuildInfo();
    }

    @Override
    public void onResume() {
        super.onResume();
        startService(Utils.APP_IN_FOREGROUND);
    }

    @Override
    public void onPause() {
        super.onPause();
        startService(Utils.APP_IN_BACKGROUND);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event){
        detector.onTouchEvent(event);
        return super.onTouchEvent(event);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.settings_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        } else if (item.getItemId() == R.id.option_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    protected static void setAppTheme(int mode) {
        switch (mode) {
            case 0:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case 1:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case 2:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }

    private void setCurrentBuildInfo() {
        ((TextView) findViewById(R.id.view_device))
            .setText(getString(R.string.device_name_text, Utils.getDevice()));
        ((TextView) findViewById(R.id.view_version))
            .setText(getString(R.string.version_text, Utils.getVersion()));
        ((TextView) findViewById(R.id.view_timestamp))
            .setText(getString(R.string.timestamp_text, Utils.getTimestamp()));
    }

    private void setWidgets() {
        refreshIcon = (ImageView) findViewById(R.id.refresh_icon);
        refreshIcon.setVisibility(VISIBLE);
        animatedDrawable = (AnimatedVectorDrawable) refreshIcon.getDrawable();
        animatedDrawable.registerAnimationCallback(new AnimationCallback() {
            @Override
            public void onAnimationEnd(Drawable drawable) {
                if (!hasUpdatedBuildInfo) {
                    ((AnimatedVectorDrawable) drawable).start();
                } else {
                    handler.post(() -> refreshIcon.setVisibility(GONE));
                }
            }
        });
        viewLatestBuild = (TextView) findViewById(R.id.view_latest_build);
        latestBuildVersion = (TextView) findViewById(R.id.view_latest_build_version);
        latestBuildTimestamp = (TextView) findViewById(R.id.view_latest_build_timestamp);
        latestBuildName = (TextView) findViewById(R.id.view_latest_build_filename);
        latestBuildMd5sum = (TextView) findViewById(R.id.view_latest_build_md5sum);

        downloadButton = (Button) findViewById(R.id.download_button);
        pauseButton = (Button) findViewById(R.id.pause_resume_button);
        cancelButton = (Button) findViewById(R.id.cancel_button);
        downloadProgressLayout = (LinearLayout) findViewById(R.id.download_progress_layout);
        downloadStatus = (TextView) findViewById(R.id.download_status);
        downloadProgressBar = (ProgressBar) findViewById(R.id.download_progress);
        downloadProgressBar.setIndeterminate(true);
        downloadProgressText = (TextView) findViewById(R.id.numeric_download_progress);

        updateButton = (Button) findViewById(R.id.update_button);
    }

    private void updateBuildInfo() {
        if (hasUpdatedBuildInfo) {
            hasUpdatedBuildInfo = false;
        } else {
            return;
        }
        refreshIcon.setVisibility(VISIBLE);
        animatedDrawable.start();
        Intent intent = new Intent(this, NetworkService.class);
        intent.putExtra(Utils.MESSAGE, Utils.FETCH_BUILD_INFO);
        startService(intent);
    }

    private void resetBuildInfo() {
        setVisibile(false, latestBuildVersion,
            latestBuildTimestamp, latestBuildName, latestBuildMd5sum);
    }

    private void restoreActivityState(Bundle bundle) {
        hasUpdatedBuildInfo = true;
        if (bundle.getBoolean(Utils.DOWNLOAD_FINISHED)) {
            downloading = false;
            downloadFinished = true;
            updateButton.setVisibility(VISIBLE);
        } else {
            downloading = true;
            downloadFinished = false;
            downloadPaused = bundle.getBoolean(Utils.DOWNLOAD_PAUSED);
        }
        setNewBuildInfo(bundle.getBundle(Utils.BUILD_INFO));
        setDownloadLayout(bundle);
    }

    private void setBuildFetchResult(int textId) {
        hasUpdatedBuildInfo = true;
        viewLatestBuild.setText(getString(textId));
    }

    private void setNewBuildInfo(Bundle bundle) {
        latestBuildVersion.setText(getString(R.string.version_text,
            bundle.getString(Utils.BUILD_VERSION)));
        latestBuildTimestamp.setText(getString(R.string.timestamp_text,
            bundle.getString(Utils.BUILD_TIMESTAMP)));
        latestBuildName.setText(getString(R.string.filename_text,
            bundle.getString(Utils.BUILD_NAME)));
        latestBuildMd5sum.setText(getString(R.string.md5sum_text,
            bundle.getString(Utils.BUILD_MD5SUM)));

        setVisibile(true, latestBuildVersion,
            latestBuildTimestamp, latestBuildName, latestBuildMd5sum);

        if (!downloading && !downloadFinished) {
            downloadButton.setVisibility(VISIBLE);
        }
    }

    private String getString(int id, String str) {
        return getString(id) + " " + str;
    }

    private void showToastAndResumeButton() {
        Toast.makeText(this, getString(R.string.no_internet), Toast.LENGTH_SHORT).show();
        downloadPaused = true;
        downloadProgressBar.setIndeterminate(true);
        downloadStatus.setText(getString(R.string.status_no_internet));
        pauseButton.setText(getString(R.string.resume_download));
    }

    private void setDownloadLayout(Bundle bundle) {
        long downloaded = bundle.getLong(Utils.DOWNLOADED_SIZE);
        long total = bundle.getLong(Utils.BUILD_SIZE);
        downloadButton.setVisibility(GONE);
        updateProgressBar((int) ((downloaded*100)/total));
        if (downloading) {
            downloadStatus.setText(getString(downloadPaused ?
                R.string.status_download_paused : R.string.status_downloading));
        } else if (downloadFinished) {
            downloadStatus.setText(getString(R.string.status_download_finished));
        }
        downloadProgressLayout.setVisibility(VISIBLE);
        if (downloading) {
            pauseButton.setText(getString(downloadPaused ?
                R.string.resume_download : R.string.pause_download));
            setVisibile(true, pauseButton, cancelButton);
        }
        updateDownloadedSize(Utils.parseProgressText(downloaded, total));
    }

    private void updateDownloadedSize(String text) {
        downloadProgressText.setText(text);
    }

    private void updateProgressBar(int progress) {
        if (downloadProgressBar.isIndeterminate()) {
            downloadProgressBar.setIndeterminate(false);
        }
        downloadProgressBar.setProgress(progress);
    }

    private void setVisibile(boolean visible, View... views) {
        for (View v: views) {
            v.setVisibility(visible ? VISIBLE : GONE);
        }
    }

    private void setDownloadFinished() {
        downloading = false;
        downloadFinished = true;
        downloadStatus.setText(getString(R.string.status_download_finished));
        setVisibile(false, downloadButton, pauseButton, cancelButton);
        updateButton.setVisibility(VISIBLE);
    }

    public void startUpdate(View v) {
        // Nothing for now
    }

    public void startDownload(View v) {
        v.performHapticFeedback(KEYBOARD_PRESS);
        v.setVisibility(GONE);
        downloading = true;
        downloadFinished = false;
        startService(Utils.START_DOWNLOAD);
    }

    public void pauseOrResumeDownload(View v) {
        v.performHapticFeedback(KEYBOARD_PRESS);
        downloadPaused = !downloadPaused;
        downloadStatus.setText(getString(downloadPaused ?
            R.string.status_download_paused : R.string.status_downloading));
        pauseButton.setText(getString(downloadPaused ?
            R.string.resume_download : R.string.pause_download));
        startService(downloadPaused ?
            Utils.PAUSE_DOWNLOAD : Utils.RESUME_DOWNLOAD);
    }

    public void cancelDownload(View v) {
        v.performHapticFeedback(KEYBOARD_PRESS);
        downloading = false;
        setVisibile(false, v, pauseButton, downloadProgressLayout);
        downloadButton.setVisibility(VISIBLE);
        startService(Utils.CANCEL_DOWNLOAD);
        AlertDialog confirmDeleteDialog = new AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle(R.string.delete_file)
            .setCancelable(false)
            .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                    dialog.dismiss();
                    startService(Utils.DELETE_DOWNLOAD);
                })
            .setNegativeButton(android.R.string.no, (dialog, which) ->
                    dialog.dismiss())
            .create();
        confirmDeleteDialog.getWindow().setBackgroundDrawable(new ColorDrawable(TRANSPARENT));
        confirmDeleteDialog.show();
    }

    private void startService(int msgId) {
        Intent intent = new Intent(this, NetworkService.class);
        intent.putExtra(Utils.MESSAGE, msgId);
        startService(intent);
    }

    private final class GestureListener extends GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onFling(MotionEvent event1, MotionEvent event2,
                float velocityX, float velocityY) {
            float diffX = event2.getRawX() - event1.getRawX();
            float diffY = event2.getRawY() - event1.getRawY();
            if (Math.abs(diffX) < 100 && diffY > 300 && !downloading) {
                updateBuildInfo();
            }
            return true;
        }
    }

    private final class UIHandler extends Handler {
        public UIHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case Utils.UPDATED_BUILD_INFO:
                    setBuildFetchResult(R.string.new_build_text);
                    setNewBuildInfo(msg.getData());
                    break;
                case Utils.FAILED_TO_UPDATE_BUILD_INFO:
                    resetBuildInfo();
                    setBuildFetchResult(R.string.unable_to_fetch_details);
                    Toast.makeText(UpdaterActivity.this,
                        getString(R.string.check_internet), Toast.LENGTH_SHORT).show();
                    break;
                case Utils.NO_NEW_BUILD_FOUND:
                    resetBuildInfo();
                    setBuildFetchResult(R.string.latest_build_text);
                    break;
                case Utils.RESTORE_STATUS:
                    setBuildFetchResult(R.string.new_build_text);
                    restoreActivityState(msg.getData());
                    break;
                case Utils.SET_INITIAL_DOWNLOAD_PROGRESS:
                    setDownloadLayout(msg.getData());
                    break;
                case Utils.UPDATE_DOWNLOADED_SIZE:
                    updateDownloadedSize((String) msg.obj);
                    break;
                case Utils.UPDATE_PROGRESS_BAR:
                    updateProgressBar(msg.arg1);
                    break;
                case Utils.NO_INTERNET:
                    showToastAndResumeButton();
                    break;
                case Utils.FINISHED_DOWNLOAD:
                    setDownloadFinished();
                    break;
                default:
                    Utils.log("Unknown message", msg.what);
            }
        }
    }
}
