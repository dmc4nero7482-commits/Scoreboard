package com.twsa.scoreboard;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import androidx.core.content.FileProvider;
import com.getcapacitor.BridgeActivity;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import java.io.File;

public class MainActivity extends BridgeActivity {

    private String pendingUrl = null;
    private String pendingFilename = null;
    private String pendingTeamA = null;
    private String pendingTeamB = null;
    private String pendingQRInputId = null;
    private String pendingQRCodeParam = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
        getBridge().getWebView().addJavascriptInterface(new UpdateBridge(), "AndroidUpdate");
        getBridge().getWebView().addJavascriptInterface(new QRBridge(), "AndroidQR");
        getBridge().getWebView().addJavascriptInterface(new TorchBridge(), "AndroidTorch");
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            Uri data = intent.getData();
            String teamA = data.getQueryParameter("teamA");
            String teamB = data.getQueryParameter("teamB");
            if (teamA == null) teamA = "";
            if (teamB == null) teamB = "";
            final String a = teamA.replace("'", "\\'");
            final String b = teamB.replace("'", "\\'");
            getBridge().getWebView().post(() ->
                getBridge().getWebView().evaluateJavascript(
                    "applyExternalTeamNames('" + a + "','" + b + "')", null));
        }
    }

    private void handleIntent(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            Uri data = intent.getData();
            pendingTeamA = data.getQueryParameter("teamA");
            pendingTeamB = data.getQueryParameter("teamB");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (pendingUrl != null && canInstall()) {
            String url = pendingUrl;
            String filename = pendingFilename;
            pendingUrl = null;
            pendingFilename = null;
            new UpdateBridge().downloadAndInstall(url, filename);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null && result.getContents() != null && pendingQRInputId != null) {
            final String raw = result.getContents().replace("'", "\\'");
            final String inputId = pendingQRInputId.replace("'", "\\'");
            final String codeParam = pendingQRCodeParam != null ? pendingQRCodeParam.replace("'", "\\'") : "";
            pendingQRInputId = null;
            pendingQRCodeParam = null;
            runOnUiThread(() ->
                getBridge().getWebView().evaluateJavascript(
                    "onQRScanResult('" + inputId + "','" + codeParam + "','" + raw + "')", null));
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private boolean canInstall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    class TorchBridge {
        @JavascriptInterface
        public void setTorch(boolean on) {
            try {
                CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                for (String id : cm.getCameraIdList()) {
                    Integer facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        cm.setTorchMode(id, on);
                        break;
                    }
                }
            } catch (Exception e) {
                android.util.Log.e("TORCH", "setTorch: " + e.getMessage());
            }
        }
    }

    class QRBridge {
        @JavascriptInterface
        public void startScan(String inputId, String codeParam) {
            pendingQRInputId = inputId;
            pendingQRCodeParam = codeParam;
            runOnUiThread(() ->
                new IntentIntegrator(MainActivity.this)
                    .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                    .setPrompt("對準 QR Code 進行掃描")
                    .setOrientationLocked(false)
                    .setBeepEnabled(true)
                    .initiateScan());
        }
    }

    class UpdateBridge {
        @JavascriptInterface
        public String getInitialTeamA() {
            return pendingTeamA != null ? pendingTeamA : "";
        }

        @JavascriptInterface
        public String getInitialTeamB() {
            return pendingTeamB != null ? pendingTeamB : "";
        }

        @JavascriptInterface
        public void downloadAndInstall(String url, String filename) {
            File destFile = new File(getExternalFilesDir(null), filename);

            debug("canInstall=" + canInstall() + " fileExists=" + destFile.exists());

            if (destFile.exists() && canInstall()) {
                debug("已有檔案且有權限，直接安裝");
                runOnUiThread(() -> installApk(destFile.getAbsolutePath()));
                return;
            }

            if (!canInstall()) {
                debug("無安裝權限，導向設定");
                pendingUrl = url;
                pendingFilename = filename;
                runOnUiThread(() -> {
                    Intent intent = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                });
                return;
            }

            if (destFile.exists()) destFile.delete();
            debug("開始下載...");

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle("正在下載更新");
            request.setDescription("羽球計分板");
            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_HIDDEN);
            request.setDestinationUri(Uri.fromFile(destFile));
            request.setMimeType("application/vnd.android.package-archive");

            long downloadId = dm.enqueue(request);
            startProgressPolling(dm, downloadId, destFile.getAbsolutePath());
        }

        private void debug(String msg) {
            android.util.Log.d("APK_INSTALL", msg);
            runOnUiThread(() ->
                getBridge().getWebView().evaluateJavascript(
                    "onDebugMsg('" + msg.replace("'", "\\'") + "')", null));
        }

        private void startProgressPolling(DownloadManager dm, long downloadId, String filePath) {
            new Thread(() -> {
                boolean running = true;
                while (running) {
                    DownloadManager.Query query = new DownloadManager.Query();
                    query.setFilterById(downloadId);
                    Cursor cursor = dm.query(query);
                    if (cursor != null && cursor.moveToFirst()) {
                        int status = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        long downloaded = cursor.getLong(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        long total = cursor.getLong(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        cursor.close();

                        if (total > 0) {
                            int percent = (int) (downloaded * 100 / total);
                            runOnUiThread(() ->
                                getBridge().getWebView().evaluateJavascript(
                                    "onDownloadProgress(" + percent + ")", null));
                        }

                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            running = false;
                            dm.remove(downloadId);
                            debug("STATUS_SUCCESSFUL，呼叫 installApk");
                            runOnUiThread(() -> installApk(filePath));
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            debug("下載失敗");
                            running = false;
                        }
                    } else {
                        if (cursor != null) cursor.close();
                        running = false;
                    }

                    try { Thread.sleep(300); } catch (InterruptedException e) { break; }
                }
            }).start();
        }

        private void installApk(String filePath) {
            debug("installApk 開始");
            try {
                File apkFile = new File(filePath);
                debug("檔案存在:" + apkFile.exists() + " 大小:" + apkFile.length());
                Uri uri = FileProvider.getUriForFile(
                    MainActivity.this,
                    getPackageName() + ".fileprovider",
                    apkFile);
                debug("URI:" + uri.toString());
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, "application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
                debug("startActivity 完成");
            } catch (Exception e) {
                debug("錯誤:" + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        }
    }
}
