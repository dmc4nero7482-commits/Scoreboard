package com.twsa.scoreboard.wear;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.wear.remote.interactions.RemoteActivityHelper;

import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 手錶端遙控畫面。
 *
 * 通訊走 Wearable Data Layer 的 MessageClient，也就是手錶與手機之間那條系統級藍牙通道，
 * 配對、重連、重試都由 Google Play 服務處理，App 不需要自己碰 GATT 或 RFCOMM。
 *
 *   手錶 → 手機   path = /sb/cmd     payload = scoreA、unscoreB、resetConfirmed…
 *                                             外加 hello / ping（要一份狀態）、closeapp
 *   手機 → 手錶   path = /sb/state   payload = 比分 JSON
 *   手機 → 手錶   path = /sb/bye     payload = 空，表示手機端要離開前景了
 *
 * 手機端沒有註冊 WearableListenerService，指令只有在計分板 App 位於前景時才會被接收，
 * 這正是實際使用情境（裁判把手機放在場邊、畫面亮著）。
 *
 * 連線狀態靠三件事維持，缺一不可：
 *   1. 手機進入前景時主動推一份狀態 —— 手錶比手機先開的情況
 *   2. 手機離開前景時送 /sb/bye    —— 手機被關掉的情況
 *   3. 手錶每 4 秒 ping 一次        —— 當機、藍牙斷線、走出距離，沒有人會通知你
 *
 * 未連線時狀態列顯示「手機未開啟 · 點此開啟」，點它就會用 RemoteActivityHelper
 * 遠端把手機端叫起來（見 openPhoneApp()）；已連線時點兩下則是把手機端收起來。
 *
 * 計時刻意不放在手錶上：一場比賽只按一兩次，卻要吃掉錶面寶貴的一整列。留在手機端操作，
 * 手錶只負責最高頻的加減分，外加一顆重置。
 */
public class WatchActivity extends Activity implements MessageClient.OnMessageReceivedListener {

    private static final String PATH_CMD   = "/sb/cmd";
    private static final String PATH_STATE = "/sb/state";
    /** 手機端即將離開前景時主動通知，讓手錶不必等心跳逾時就能改顯示 */
    private static final String PATH_BYE   = "/sb/bye";

    /** 送出指令後多久沒收到狀態回應就視為手機端沒在聽 */
    private static final long ACK_TIMEOUT_MS = 3000L;

    /**
     * 心跳間隔。手錶原本只在 onResume 探一次，之後手機才開啟的話手錶不會知道，
     * 會一直停在「手機未開啟」。定期輕量詢問是唯一能涵蓋所有情況的做法 ——
     * 手機當掉、藍牙斷線、走出距離都不會有人通知你。
     */
    private static final long HEARTBEAT_MS = 4000L;

    /** 超過這段時間沒收到任何狀態就視為斷線（要能容忍掉一兩次心跳） */
    private static final long LIVENESS_TIMEOUT_MS = 10000L;

    /** 未連線時狀態列的固定提示，點它就會遠端喚起手機端 */
    private static final String HINT_TAP_TO_OPEN = "手機未開啟 · 點此開啟";

    /** 重置按鈕按第一下之後，等待第二下確認的時間 */
    private static final long RESET_ARM_MS = 3000L;

    /**
     * 手機端計分板的 deep link。MainActivity 早就在 manifest 註冊了這個 scheme
     * （含 BROWSABLE category、launchMode=singleTask），但一直沒被用到。
     */
    private static final String PHONE_DEEP_LINK = "twsa-scoreboard://start";

    /** 遠端喚起手機後，等多久再去確認它真的接上了 */
    private static final long RELAUNCH_PROBE_MS = 2500L;

    /** 判定為上下滑動所需的最小垂直位移（24dp 換算成 px，onCreate 時算好） */
    private int flingMinDistancePx;

    /** 重置按鈕是否已進入待確認狀態 */
    private boolean resetArmed = false;

    /** 狀態列是否已進入「再按一次關閉手機端」的待確認狀態 */
    private boolean closeArmed = false;

    /**
     * 最近一次由 applyState() 算出來的狀態列文字（「● 第N局 x:y」）。
     * 待確認提示取消後要把它放回去，否則局數資訊會消失到下一次狀態推播為止。
     */
    private String linkedStatusText = null;

    private TextView tvStatus, tvNameA, tvNameB, tvScoreA, tvScoreB, btnMinusA, btnMinusB, btnReset;
    private View panelA, panelB;

    private Vibrator vibrator;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** RemoteActivityHelper 的工作執行緒；不要拿主執行緒去跑它的 IPC */
    private final ExecutorService remoteExecutor = Executors.newSingleThreadExecutor();

    /** 是否曾經收到手機回應（收到才算真的接上計分板 App） */
    private boolean linked = false;

    /** 最近一次收到手機狀態的時刻（elapsedRealtime），用來判斷連線是否還活著 */
    private long lastStateAt = 0L;

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (linked && SystemClock.elapsedRealtime() - lastStateAt > LIVENESS_TIMEOUT_MS) {
                markUnlinked();
            }
            // ping 不經過網頁層，手機端原生直接回推一份狀態，不會被當成一次遙控操作
            sendCmd("ping", 0);
            handler.postDelayed(this, HEARTBEAT_MS);
        }
    };

    private final Runnable disarmReset = new Runnable() {
        @Override public void run() {
            resetArmed = false;
            btnReset.setText("↺");
        }
    };

    private final Runnable disarmClose = new Runnable() {
        @Override public void run() {
            closeArmed = false;
            if (linked && linkedStatusText != null) {
                tvStatus.setText(linkedStatusText);
                tvStatus.setTextColor(0xFF4CAF50);
            } else if (linked) {
                setStatus("已連線", true);
            } else {
                setStatus(HINT_TAP_TO_OPEN, false);
            }
        }
    };

    private final Runnable ackTimeout = () -> {
        if (!linked) setStatus(HINT_TAP_TO_OPEN, false);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_watch);
        // 比賽中手一直在按，不要讓螢幕暗掉
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        flingMinDistancePx = Math.round(24f * getResources().getDisplayMetrics().density);

        tvStatus  = findViewById(R.id.tvStatus);
        tvNameA   = findViewById(R.id.tvNameA);
        tvNameB   = findViewById(R.id.tvNameB);
        tvScoreA  = findViewById(R.id.tvScoreA);
        tvScoreB  = findViewById(R.id.tvScoreB);
        btnMinusA = findViewById(R.id.btnMinusA);
        btnMinusB = findViewById(R.id.btnMinusB);
        btnReset  = findViewById(R.id.btnReset);
        panelA    = findViewById(R.id.panelA);
        panelB    = findViewById(R.id.panelB);

        // 加分：點色塊，或在色塊上往上滑。減分：色塊內的 −1 按鈕，或往下滑。
        // 滑動在比賽中不必瞄準，−1 按鈕則是戴手套、手濕時比較保險的退路。
        attachScoreGestures(panelA, "scoreA", "unscoreA");
        attachScoreGestures(panelB, "scoreB", "unscoreB");
        btnMinusA.setOnClickListener(v -> sendCmd("unscoreA", 60));
        btnMinusB.setOnClickListener(v -> sendCmd("unscoreB", 60));

        // 重置要防誤觸，但長按在手錶上很不好按（手指得穩穩壓住小按鈕）。
        // 改成點兩下：第一下進入待確認並顯示 ✓，RESET_ARM_MS 內再點一下才真的送出。
        btnReset.setOnClickListener(v -> {
            if (resetArmed) {
                handler.removeCallbacks(disarmReset);
                disarmReset.run();
                sendCmd("resetConfirmed", 120);
            } else {
                resetArmed = true;
                btnReset.setText("✓");
                vibrate(20);
                Toast.makeText(this, "再按一次重置比賽", Toast.LENGTH_SHORT).show();
                handler.postDelayed(disarmReset, RESET_ARM_MS);
            }
        });

        // 狀態列兼作手機端的電源開關：沒連上就開啟它，連上了就（點兩下）把它收起來。
        // 做在狀態列上是因為錶面實在沒有空間再多一顆按鈕，而這個操作的時機
        // 剛好就是使用者在看狀態列的時候。
        tvStatus.setClickable(true);
        tvStatus.setFocusable(true);
        tvStatus.setOnClickListener(v -> onStatusTapped());
    }

    // ── 連線狀態 ──────────────────────────────────────────────

    /** 降級為未連線並更新提示。待確認提示正顯示時不要蓋掉它。 */
    private void markUnlinked() {
        linked = false;
        linkedStatusText = null;
        if (!closeArmed) setStatus(HINT_TAP_TO_OPEN, false);
    }

    // ── 遠端開關手機端 App ────────────────────────────────────

    private void onStatusTapped() {
        if (!linked) {
            openPhoneApp();
            return;
        }
        if (closeArmed) {
            handler.removeCallbacks(disarmClose);
            closeArmed = false;
            sendCmd("closeapp", 60);
            linked = false;
            linkedStatusText = null;
            setStatus("已要求關閉手機端", false);
            // 手機收起來之後就不會再回狀態了，把提示換成可再次開啟的樣子
            handler.postDelayed(() -> {
                if (!linked) setStatus(HINT_TAP_TO_OPEN, false);
            }, 2000L);
        } else {
            closeArmed = true;
            vibrate(20);
            setStatus("再按一次關閉手機端", false);
            handler.postDelayed(disarmClose, RESET_ARM_MS);
        }
    }

    /**
     * 從手錶把手機上的計分板叫起來。
     *
     * 不能改成「手機端註冊 WearableListenerService，收到訊息就 startActivity」——
     * Android 10 起禁止背景啟動 Activity，那樣會被系統無聲擋掉。
     * RemoteActivityHelper 是把 deep link 交給手機上的 Wear companion App 代為開啟，
     * 由它（系統層級）執行啟動，不受背景限制，也不需要額外權限。
     */
    private void openPhoneApp() {
        android.util.Log.d("WEAR", "openPhoneApp: " + PHONE_DEEP_LINK);
        setStatus("正在開啟手機…", false);
        vibrate(30);
        Intent intent = new Intent(Intent.ACTION_VIEW)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setData(Uri.parse(PHONE_DEEP_LINK));
        try {
            final ListenableFuture<Void> future =
                new RemoteActivityHelper(this, remoteExecutor).startRemoteActivity(intent);
            future.addListener(() -> {
                try {
                    future.get();
                    android.util.Log.d("WEAR", "遠端開啟要求已送達 companion");
                    // companion 收下了不代表 App 已經就緒，隔一下再探一次連線
                    handler.postDelayed(() -> {
                        sendCmd("hello", 0);
                        handler.removeCallbacks(ackTimeout);
                        handler.postDelayed(ackTimeout, ACK_TIMEOUT_MS);
                    }, RELAUNCH_PROBE_MS);
                } catch (Exception e) {
                    android.util.Log.w("WEAR", "遠端開啟失敗: " + e);
                    setStatus("無法開啟手機端", false);
                }
            }, ContextCompat.getMainExecutor(this));
        } catch (Exception e) {
            setStatus("無法開啟手機端", false);
        }
    }

    /**
     * 把「點＝加分、上滑＝加分、下滑＝減分、長按＝減分」綁到一個計分色塊上。
     *
     * 判定成滑動時必須把事件吞掉，否則會重複計分：View 只有在手指移出「自己的範圍」
     * 才會取消點擊，色塊很大，整段滑動都還在框內，ACTION_UP 照樣被判定成點擊 ——
     * 下滑會變成先 −1 再 +1，等於沒作用。這是實機 log 抓出來的。
     *
     * 吞掉 UP 還不夠：長按是在 ACTION_DOWN 時就排進 handler 的，沒收到 UP 就不會被
     * 取消，500ms 後照樣觸發。所以補送一個 ACTION_CANCEL 給 View，一次清掉按下狀態
     * 與待觸發的長按。
     */
    private void attachScoreGestures(View panel, String addCmd, String subCmd) {
        final GestureDetector detector = new GestureDetector(this,
            new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onFling(MotionEvent down, MotionEvent up, float vx, float vy) {
                    if (down == null || up == null) return false;
                    float dy = up.getY() - down.getY();
                    float dx = up.getX() - down.getX();
                    // 垂直位移要夠大、而且明顯比水平大，才算加減分。
                    // 水平滑動留給 Wear OS 的滑動返回手勢，不要搶。
                    if (Math.abs(dy) < flingMinDistancePx || Math.abs(dy) <= Math.abs(dx)) return false;
                    if (dy < 0) sendCmd(addCmd, 30);
                    else        sendCmd(subCmd, 60);
                    return true;
                }
            });

        panel.setOnClickListener(v -> sendCmd(addCmd, 30));
        panel.setOnLongClickListener(v -> { sendCmd(subCmd, 60); return true; });
        panel.setOnTouchListener((v, e) -> {
            if (!detector.onTouchEvent(e)) return false;
            MotionEvent cancel = MotionEvent.obtain(e);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            v.onTouchEvent(cancel);
            cancel.recycle();
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Wearable.getMessageClient(this).addListener(this);
        linked = false;
        linkedStatusText = null;
        lastStateAt = 0L;
        setStatus("連線中…", false);
        // 要一份目前比分，順便確認手機端有在聽
        sendCmd("hello", 0);
        handler.postDelayed(ackTimeout, ACK_TIMEOUT_MS);
        // 心跳負責兩件事：手機是後來才開的要能自動接上，手機不見了也要能自動降級
        handler.removeCallbacks(heartbeat);
        handler.postDelayed(heartbeat, HEARTBEAT_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Wearable.getMessageClient(this).removeListener(this);
        handler.removeCallbacks(ackTimeout);
        handler.removeCallbacks(disarmReset);
        handler.removeCallbacks(disarmClose);
        handler.removeCallbacks(heartbeat);
        closeArmed = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        remoteExecutor.shutdown();
    }

    // ── 送指令給手機 ──────────────────────────────────────────

    private void sendCmd(String cmd, int vibrateMs) {
        if (vibrateMs > 0) vibrate(vibrateMs);
        final byte[] payload = cmd.getBytes(StandardCharsets.UTF_8);

        Wearable.getNodeClient(this).getConnectedNodes()
            .addOnSuccessListener(nodes -> {
                android.util.Log.d("WEAR", "sendCmd " + cmd + " -> "
                    + (nodes == null ? 0 : nodes.size()) + " node(s)");
                if (nodes == null || nodes.isEmpty()) {
                    linked = false;
                    linkedStatusText = null;
                    if (!closeArmed) setStatus("找不到已配對手機", false);
                    return;
                }
                for (Node node : nodes) {
                    Wearable.getMessageClient(WatchActivity.this)
                        .sendMessage(node.getId(), PATH_CMD, payload)
                        .addOnFailureListener(e -> setStatus("傳送失敗", false));
                }
            })
            .addOnFailureListener(e -> {
                linked = false;
                linkedStatusText = null;
                if (!closeArmed) setStatus("藍牙未連線", false);
            });
    }

    // ── 收手機回推的比分 ──────────────────────────────────────

    @Override
    public void onMessageReceived(MessageEvent event) {
        // 手機端要離開前景了。等心跳逾時要 10 秒，這段期間手錶會顯示一個已經不成立的
        // 「已連線」，使用者會對著沒人在聽的畫面加分。主動通知就能立刻反映。
        if (PATH_BYE.equals(event.getPath())) {
            android.util.Log.d("WEAR", "收到手機端離開通知");
            runOnUiThread(this::markUnlinked);
            return;
        }
        if (!PATH_STATE.equals(event.getPath())) return;
        final String json = new String(event.getData(), StandardCharsets.UTF_8);
        android.util.Log.d("WEAR", "收到狀態 " + json.length() + " bytes");
        runOnUiThread(() -> {
            handler.removeCallbacks(ackTimeout);
            linked = true;
            lastStateAt = SystemClock.elapsedRealtime();
            if (!closeArmed) setStatus("已連線", true);
            applyState(json);
        });
    }

    private void applyState(String json) {
        try {
            JSONObject o = new JSONObject(json);
            tvScoreA.setText(String.valueOf(o.optInt("scoreA", 0)));
            tvScoreB.setText(String.valueOf(o.optInt("scoreB", 0)));
            tvNameA.setText(shorten(o.optString("nameA", "A")));
            tvNameB.setText(shorten(o.optString("nameB", "B")));

            // 局數比分放在上方狀態列。連線後「已連線」這三個字沒有資訊價值，
            // 直接讓位給局數；綠點本身就表示連線正常。
            linkedStatusText = String.format(Locale.US, "● 第%d局  %d:%d",
                o.optInt("currentSet", 1), o.optInt("setsA", 0), o.optInt("setsB", 0));
            // 待確認提示正顯示在狀態列上時不要蓋掉它，否則使用者會看不出自己按了什麼
            if (!closeArmed) {
                tvStatus.setText(linkedStatusText);
                tvStatus.setTextColor(0xFF4CAF50);
            }
        } catch (Exception e) {
            // 狀態格式不對就維持畫面現狀，不要讓遙控器整個掛掉
        }
    }

    /** 手錶畫面窄，隊名超過 5 個字就截斷 */
    private String shorten(String s) {
        if (s == null || s.isEmpty()) return "—";
        return s.length() > 5 ? s.substring(0, 5) : s;
    }

    private void setStatus(String msg, boolean ok) {
        runOnUiThread(() -> {
            tvStatus.setText((ok ? "● " : "○ ") + msg);
            tvStatus.setTextColor(ok ? 0xFF4CAF50 : 0xFF8899AA);
        });
    }

    private void vibrate(int ms) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(ms);
        }
    }
}
