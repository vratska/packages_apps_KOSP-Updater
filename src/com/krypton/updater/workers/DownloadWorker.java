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

package com.krypton.updater.workers;

import static android.os.Process.THREAD_PRIORITY_BACKGROUND;
import static com.krypton.updater.util.Constants.UPDATE_PENDING;
import static com.krypton.updater.util.Constants.BUILD_SIZE;
import static com.krypton.updater.util.Constants.BUILD_MD5;
import static com.krypton.updater.util.Constants.BUILD_URL;
import static com.krypton.updater.util.Constants.BUILD_NAME;
import static com.krypton.updater.util.Constants.DOWNLOADING;
import static com.krypton.updater.util.Constants.FINISHED;
import static com.krypton.updater.util.Constants.FAILED;

import android.content.Context;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.ListenableWorker.Result;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.krypton.updater.model.room.DownloadStatusDao;
import com.krypton.updater.model.room.DownloadStatusEntity;
import com.krypton.updater.model.room.AppDatabase;
import com.krypton.updater.R;
import com.krypton.updater.util.NotificationHelper;
import com.krypton.updater.model.data.OTAFileManager;
import com.krypton.updater.util.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;

public class DownloadWorker extends Worker {
    private static final String TAG = "DownloadWorker";
    private static final int UPDATER_DOWNLOAD_NOTIF_ID = 1003;
    private static final int BUF_SIZE = 8192; // 8 KB
    private final Context context;
    private final OTAFileManager ofm;
    private final DownloadStatusDao dao;
    private final Handler handler;
    private final HandlerThread thread;
    private final NotificationHelper helper;
    private final AppDatabase database;
    private final NotificationCompat.Builder notificationBuilder;
    private final ProgressRunnable progressRunnable;
    private int currPercent = 0;
    private long currSize = 0;
    private long totalSize = 0;

    public DownloadWorker(Context context, WorkerParameters parameters,
            AppDatabase database, NotificationHelper helper, OTAFileManager ofm) {
        super(context, parameters);
        this.context = context;
        this.database = database;
        this.helper = helper;
        this.ofm = ofm;
        dao = database.getDownloadStatusDao();
        thread = new HandlerThread(TAG, THREAD_PRIORITY_BACKGROUND);
        thread.start();
        handler = new Handler(thread.getLooper());
        notificationBuilder = helper.getDefaultBuilder()
            .setNotificationSilent()
            .setOngoing(true)
            .setContentTitle(context.getString(R.string.downloading));
        progressRunnable = new ProgressRunnable();
    }

    @Override
    public Result doWork() {
        final Data inputData = getInputData();
        totalSize = inputData.getLong(BUILD_SIZE, 0);
        int exitCode = download(inputData.getString(BUILD_URL),
            inputData.getString(BUILD_NAME), inputData.getString(BUILD_MD5));
        final Result result;
        switch (exitCode) {
            case -1:
                updateStatusAsync(FAILED);
                result = Result.failure();
                break;
            case 0:
                result = Result.retry();
                break;
            case 1:
                updateStatusAsync(FINISHED);
                result = Result.success();
                break;
            case 2:
                result = Result.success();
                break;
            default:
                result = Result.failure();
        }
        thread.quitSafely();
        return result;
    }

    /* return value:
     *   -1 for failure
     *    0 for retry
     *    1 for success
     *    2 if download was stopped (could be pause, cancel, or constraints not met)
     */
    private int download(String urlString, String fileName, String md5) {
        final DownloadStatusEntity entity = dao.getCurrentStatus();
        if (entity == null) {
            helper.notifyOrToast(R.string.download_failed,
                R.string.database_corrupt, handler);
            return -1;
        }
        notificationBuilder.setContentText(fileName);
        setForegroundAsync(getForegroundInfo(0, true));
        File file = new File(context.getExternalCacheDir(), fileName);
        long startByte = 0;
        boolean append = file.isFile();
        if (append) {
            currSize = file.length();
            if (entity.downloadedSize != currSize) { // Corrupt download or some other file with the same name
                currSize = 0;
                append = false;
            } else {
                startByte += currSize;
            }
        }
        URL url;
        try {
            url = new URL(urlString);
        } catch (MalformedURLException e) {
            Utils.log(e);
            helper.notifyOrToast(R.string.download_failed,
                R.string.invalid_url, handler);
            return -1;
        }
        URLConnection connection;
        try {
            connection = url.openConnection();
        } catch (IOException e) {
            Utils.log(e);
            return 0;
        }
        if (append) {
            connection.setRequestProperty("Range", String.format("bytes=%d-", startByte));
        }
        updateStatusAsync(DOWNLOADING);
        // Download starts here
        try (FileOutputStream outStream = new FileOutputStream(file, append);
                InputStream inStream = connection.getInputStream()) {
            final byte[] buffer = new byte[BUF_SIZE];
            int bytesRead = 0;
            while (!isStopped() && (bytesRead = inStream.read(buffer)) != -1) {
                outStream.write(buffer, 0, bytesRead);
                currSize += bytesRead;
                int tmp = (int) ((currSize*100)/totalSize);
                if (tmp > currPercent) {
                    currPercent = tmp;
                    setForegroundAsync(getForegroundInfo(currPercent, false)); // Update notification
                }
                updateProgressAsync(currSize, currPercent); // Update database
            }
            if (isStopped()) {
                return 2;
            }
        } catch (IOException e) {
            Utils.log(e);
            return 0;
        }
        // Check if download is actually over
        if (currSize == totalSize) {
            try (FileInputStream inStream = new FileInputStream(file)) {
                if (Utils.computeMd5(inStream).equals(md5)) {
                    if (copyFile(inStream, fileName)) {
                        file.delete();
                        helper.onlyNotify(R.string.download_finished, R.string.click_to_update);
                        // Mark as download finished and an update installation is pending
                        database.getGlobalStatusDao().updateCurrentStatus(UPDATE_PENDING);
                        return 1;
                    } else {
                        helper.notifyOrToast(R.string.download_failed,
                            R.string.copy_failed, handler);
                        return -1;
                    }
                } else {
                    helper.notifyOrToast(R.string.download_failed,
                        R.string.md5_check_failed, handler);
                    return -1;
                }
            } catch(IOException e) {
                Utils.log(e);
                helper.notifyOrToast(R.string.download_failed,
                    R.string.check_logs, handler);
                return -1;
            }
        } else {
            return 0; // Retry download
        }
    }

    private void updateStatusAsync(int status) {
        handler.post(() -> dao.updateStatus(status));
    }

    private void updateProgressAsync(long size, int percent) {
        handler.post(progressRunnable.setProgress(size, percent));
    }

    private ForegroundInfo getForegroundInfo(int progress, boolean indeterminate) {
        return new ForegroundInfo(UPDATER_DOWNLOAD_NOTIF_ID, notificationBuilder
            .setProgress(100, progress, indeterminate)
            .build());
    }

    // Copy downloaded file to Downloads folder and then to ota dir (/data/kosp_ota)
    private boolean copyFile(FileInputStream inStream, String fileName) {
        try (FileOutputStream outStream = new FileOutputStream(
                Utils.getDownloadFile(fileName))) {
            FileUtils.copy(inStream, outStream);
            return ofm.copyToOTAPackageDir(inStream);
        } catch (IOException e) {
            Utils.log(e);
        }
        return false;
    }

    // Custom runnable to update progress in database
    private final class ProgressRunnable implements Runnable {
        private int percent;
        private long size;

        public ProgressRunnable setProgress(long size, int percent) {
            this.size = size;
            this.percent = percent;
            return this;
        }

        @Override
        public void run() {
            dao.updateProgress(size, percent);
        }
    }
}