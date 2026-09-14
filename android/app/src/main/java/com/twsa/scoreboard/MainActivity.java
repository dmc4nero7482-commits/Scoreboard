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
import android.os.SystemClock;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import androidx.core.content.FileProvider;
import com.getcapacitor.BridgeActivity;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BridgeActivity {

    /** 手錶 → 手機：遙控指令 */
    private static final String WEAR_PATH_CMD = "/sb/cmd";
    /** 手機 → 手錶：比分狀態 */
    private static final String WEAR_PATH_STATE = "/sb/state";
    /** 手機 → 手錶：本端即將離開前景，指令不會再被接收 */
    private static final String WEAR_PATH_BYE = "/sb/bye";
    /** 已連線手錶清單的快取有效時間 */
    private static final long WEAR_NODES_TTL_MS = 10_000L;

    private String pendingUrl = null;
    private String pendingFilename = null;
    private String pendingTeamA = null;
    private String pendingTeamB = null;
    private String pendingQRInputId = null;
    private String pendingQRCodeParam = null;

    private MessageClient.OnMessageReceivedListener wearListener = null;
    private final List<String> wearNodeIds = new ArrayList<>();
    private long wearNodesFetchedAt = 0L;
    /** 最近送指令過來的手錶節點，狀態回覆一律以它兜底 */
    private volatile String lastWearSenderId = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
        getBridge().getWebView().addJavascriptInterface(new UpdateBridge(), "AndroidUpdate");
        getBridge().getWebView().addJavascriptInterface(new QRBridge(), "AndroidQR");
        getBridge().getWebView().addJavascriptInterface(new TorchBridge(), "AndroidTorch");
        getBridge().getWebView().addJavascriptInterface(new WearBridge(), "AndroidWear");

        wearListener = messageEvent -> {
            if (!WEAR_PATH_CMD.equals(messageEvent.getPath())) return;
            lastWearSenderId = messageEvent.getSourceNodeId();
            String raw = new String(messageEvent.getData(), StandardCharsets.UTF_8);
            // 指令來自我們自己的手錶 App，但仍過濾成純字母，避免任何字串被塞進 evaluateJavascript
            final String cmd = raw.replaceAll("[^A-Za-z]", "");
            if (cmd.isEmpty()) return;
            // 「從手錶收起手機端」是原生層的事，網頁不需要知道。
            // 用 moveTaskToBack 而不是 finish()：畫面收起來但比分與連線狀態都留著，
            // 手錶之後用 deep link 叫回來（launchMode=singleTask）就是原本那個畫面。
            if ("closeapp".equals(cmd)) {
                runOnUiThread(() -> moveTaskToBack(true));
                return;
            }
            // 心跳：只回推一份目前狀態，不要走 onWearCmd。走了的話網頁層會把每次心跳
            // 都記成一次遙控操作，手機上的手錶狀態就會永遠停在「手錶遙控中」。
            if ("ping".equals(cmd)) {
                runOnUiThread(() ->
                    getBridge().getWebView().evaluateJavascript("pushWearState()", null));
                return;
            }
            runOnUiThread(() ->
                getBridge().getWebView().evaluateJavascript("onWearCmd('" + cmd + "')", null));
        };
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
        // 只在前景監聽手錶指令；App 退到背景時 WebView 的 JS 本來就會被節流，
        // 沒有繼續收指令的意義，也省得背景耗電。
        if (wearListener != null) {
            try {
                Wearable.getMessageClient(this).addListener(wearListener);
                refreshWearNodes();
                // 主動報到。手錶若比手機先開，它那邊的探詢早就逾時了，沒有這一步
                // 就得等它下一次心跳才會發現我們上線。
                getBridge().getWebView().post(() ->
                    getBridge().getWebView().evaluateJavascript("pushWearState()", null));
            } catch (Exception e) {
                android.util.Log.w("WEAR", "addListener 失敗: " + e.getMessage());
            }
        }
    }

    @Override
    public void onPause() {
        if (wearListener != null) {
            try {
                // 先道別再解除監聽。手錶那端若不知道我們離開了，會對著一個
                // 已經沒人在聽的「已連線」畫面繼續加分，要等心跳逾時才發現。
                sendWearBye();
                Wearable.getMessageClient(this).removeListener(wearListener);
            } catch (Exception e) {
                android.util.Log.w("WEAR", "removeListener 失敗: " + e.getMessage());
            }
        }
        super.onPause();
    }

    /** 告訴手錶「本端要離開前景了」。送不出去也無所謂，手錶的心跳會兜底。 */
    private void sendWearBye() {
        final List<String> targets;
        synchronized (wearNodeIds) {
            targets = new ArrayList<>(wearNodeIds);
        }
        String sender = lastWearSenderId;
        if (sender != null && !targets.contains(sender)) targets.add(sender);
        final byte[] payload = new byte[0];
        for (String nodeId : targets) {
            try {
                Wearable.getMessageClient(this).sendMessage(nodeId, WEAR_PATH_BYE, payload);
            } catch (Exception e) {
                android.util.Log.w("WEAR", "sendWearBye 失敗: " + e.getMessage());
            }
        }
    }

    /** 重新抓一次已連線的手錶節點，並把數量回報給計分板畫面。 */
    private void refreshWearNodes() {
        try {
            Wearable.getNodeClient(this).getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    synchronized (wearNodeIds) {
                        wearNodeIds.clear();
                        for (Node n : nodes) wearNodeIds.add(n.getId());
                        wearNodesFetchedAt = SystemClock.elapsedRealtime();
                    }
                    final int count = nodes.size();
                    runOnUiThread(() ->
                        getBridge().getWebView().evaluateJavascript("onWearNodes(" + count + ")", null));
                })
                .addOnFailureListener(e -> runOnUiThread(() ->
                    getBridge().getWebView().evaluateJavascript("onWearNodes(0)", null)));
        } catch (Exception e) {
            android.util.Log.w("WEAR", "getConnectedNodes 失敗: " + e.getMessage());
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

    /**
     * 提供給 scoreboard.html 的手錶橋接介面（window.AndroidWear）。
     * 反方向（手錶 → 網頁）走 wearListener → onWearCmd()。
     */
    class WearBridge {

        /** 這台裝置是否具備 Wearable Data Layer 能力（需要 Google Play 服務）。 */
        @JavascriptInterface
        public boolean isAvailable() {
            try {
                return GoogleApiAvailability.getInstance()
                    .isGooglePlayServicesAvailable(MainActivity.this) == ConnectionResult.SUCCESS;
            } catch (Exception e) {
                return false;
            }
        }

        /** 要求重新偵測已連線手錶，結果透過 onWearNodes(n) 回呼。 */
        @JavascriptInterface
        public void refreshNodes() {
            runOnUiThread(MainActivity.this::refreshWearNodes);
        }

        /** 把目前比分推給手錶。json 由 pushWearState() 組出。 */
        @JavascriptInterface
        public void pushState(String json) {
            final byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            final List<String> targets;
            final boolean stale;
            synchronized (wearNodeIds) {
                targets = new ArrayList<>(wearNodeIds);
                stale = SystemClock.elapsedRealtime() - wearNodesFetchedAt > WEAR_NODES_TTL_MS;
            }
            // 一定要回覆給「剛剛送指令來的那支手錶」。getConnectedNodes() 是非同步的，
            // 在它回來之前（或偶爾回空清單時）targets 會是空的，狀態就靜悄悄地送不出去，
            // 症狀是手錶能單向遙控、但分數永遠停在 0。以來源節點兜底，回覆路徑就不依賴快取。
            final String sender = lastWearSenderId;
            if (sender != null && !targets.contains(sender)) targets.add(sender);
            android.util.Log.d("WEAR", "pushState -> " + targets.size() + " node(s), sender=" + sender);
            if (stale) runOnUiThread(MainActivity.this::refreshWearNodes);
            for (String nodeId : targets) {
                try {
                    Wearable.getMessageClient(MainActivity.this)
                        .sendMessage(nodeId, WEAR_PATH_STATE, payload);
                } catch (Exception e) {
                    android.util.Log.w("WEAR", "pushState 失敗: " + e.getMessage());
                }
            }
        }
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

            // 沿用既有檔案前必須確認它是完整的 APK。下載失敗（例如 Release 還沒
            // 發佈導致 404）時 DownloadManager 可能留下 0 byte 或半截的檔案，
            // 若直接拿去安裝會變成「無法解析套件」，而且因為檔案一直在，
            // 之後每次按更新都會重蹈覆轍。
            boolean reusable = isCompleteApk(destFile);
            debug("canInstall=" + canInstall() + " fileExists=" + destFile.exists()
                + " reusable=" + reusable);

            if (destFile.exists() && !reusable) {
                debug("既有檔案不完整，刪除後重新下載");
                destFile.delete();
            }

            if (reusable && canInstall()) {
                debug("已有完整檔案且有權限，直接安裝");
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

        /**
         * 檔案是否為一份完整可安裝的 APK。
         * 用 ZipFile 開啟並確認找得到 AndroidManifest.xml —— ZipFile 必須讀到
         * 檔案結尾的中央目錄才能建立，所以半截的下載一定會在這裡失敗。
         */
        private boolean isCompleteApk(File f) {
            if (f == null || !f.exists() || f.length() <= 0) return false;
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(f)) {
                return zip.getEntry("AndroidManifest.xml") != null;
            } catch (Exception e) {
                return false;
            }
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
                            // 這裡絕對不能呼叫 dm.remove()。它會把下載好的檔案一併刪掉，
                            // 即使目的地是 setDestinationUri() 指定的本 App 目錄也一樣。
                            // 檔案沒了，安裝 Intent 照樣送得出去（debug 會一路印到
                            // 「startActivity 完成」），但系統安裝程式讀到的是空檔，
                            // 只會回「應用程式的檔案發生問題」—— 看起來像 APK 壞掉，
                            // 其實是我們自己刪的。DownloadManager 裡留下的那筆記錄無害，
                            // 檔案本身在下次更新時會被 downloadAndInstall() 覆蓋。
                            File apkOnDisk = new File(filePath);
                            if (!isCompleteApk(apkOnDisk)) {
                                debug("下載回報成功但檔案不完整（" + apkOnDisk.length()
                                    + " bytes），不進行安裝");
                            } else {
                                debug("STATUS_SUCCESSFUL，呼叫 installApk");
                                runOnUiThread(() -> installApk(filePath));
                            }
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
                // 檔案不見或是空的就別再往下走。FileProvider 與 startActivity 都不會
                // 檢查檔案內容，硬送出去只會讓系統安裝程式報一個看不出原因的錯。
                if (!isCompleteApk(apkFile)) {
                    debug("APK 不完整或不存在，中止安裝");
                    return;
                }
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
