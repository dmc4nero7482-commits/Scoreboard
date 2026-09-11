package com.twsa.scoreboard.wear;

import android.app.Activity;
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

import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 手錶端遙控畫面。
 *
 * 通訊走 Wearable Data Layer 的 MessageClient，也就是手錶與手機之間那條系統級藍牙通道，
 * 配對、重連、重試都由 Google Play 服務處理，App 不需要自己碰 GATT 或 RFCOMM。
 *
 *   手錶 → 手機   path = /sb/cmd     payload = 指令字串（scoreA、unscoreB、timer …）
 *   手機 → 手錶   path = /sb/state   payload = 比分 JSON
 *
 * 手機端沒有註冊 WearableListenerService，指令只有在計分板 App 位於前景時才會被接收，
 * 這正是實際使用情境（裁判把手機放在場邊、畫面亮著）。手錶收不到 /sb/state 回應時
 * 會顯示「手機未開啟」，避免使用者以為按了有作用。
 */
public class WatchActivity extends Activity implements MessageClient.OnMessageReceivedListener {

    private static final String PATH_CMD   = "/sb/cmd";
    private static final String PATH_STATE = "/sb/state";

    /** 送出指令後多久沒收到狀態回應就視為手機端沒在聽 */
    private static final long ACK_TIMEOUT_MS = 3000L;

    /** 重置按鈕按第一下之後，等待第二下確認的時間 */
    private static final long RESET_ARM_MS = 3000L;

    /** 判定為上下滑動所需的最小垂直位移（24dp 換算成 px，onCreate 時算好） */
    private int flingMinDistancePx;

    /** 重置按鈕是否已進入待確認狀態 */
    private boolean resetArmed = false;

    private TextView tvStatus, tvNameA, tvNameB, tvScoreA, tvScoreB, tvTimer, btnTimer, btnReset;
    private View panelA, panelB;

    private Vibrator vibrator;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 是否曾經收到手機回應（收到才算真的接上計分板 App） */
    private boolean linked = false;

    // 本機計時器顯示：以手機送來的基準值 + 自行推算的經過時間
    private boolean timerRunning = false;
    private long timerBaseMs = 0L;
    private long timerSyncedAt = 0L;

    private final Runnable disarmReset = new Runnable() {
        @Override public void run() {
            resetArmed = false;
            btnReset.setText("↺");
        }
    };

    private final Runnable ackTimeout = () -> {
        if (!linked) setStatus("手機未開啟計分板", false);
    };

    private final Runnable timerTick = new Runnable() {
        @Override public void run() {
            renderTimer();
            if (timerRunning) handler.postDelayed(this, 500L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_watch);
        // 比賽中手一直在按，不要讓螢幕暗掉
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        flingMinDistancePx = Math.round(24f * getResources().getDisplayMetrics().density);

        tvStatus = findViewById(R.id.tvStatus);
        tvNameA  = findViewById(R.id.tvNameA);
        tvNameB  = findViewById(R.id.tvNameB);
        tvScoreA = findViewById(R.id.tvScoreA);
        tvScoreB = findViewById(R.id.tvScoreB);
        tvTimer  = findViewById(R.id.tvTimer);
        btnTimer = findViewById(R.id.btnTimer);
        btnReset = findViewById(R.id.btnReset);
        panelA   = findViewById(R.id.panelA);
        panelB   = findViewById(R.id.panelB);

        // 計分：點一下 +1、往上滑 +1、往下滑 −1、長按 −1。
        // 滑動是實際比賽時最順手的動作（手指不必瞄準，划過去就好），
        // 點按與長按保留下來，戴手套或手濕時比較好按。
        attachScoreGestures(panelA, "scoreA", "unscoreA");
        attachScoreGestures(panelB, "scoreB", "unscoreB");

        btnTimer.setOnClickListener(v -> sendCmd("timer", 30));

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
    }

    /**
     * 把「點＝加分、上滑＝加分、下滑＝減分、長按＝減分」綁到一個計分色塊上。
     *
     * onTouch 回傳 false，事件才會繼續交給 View 自己的點擊／長按處理；
     * 手指滑超過 touch slop 時 View 本來就不會再判定為點擊，所以滑動不會重複計分。
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
        panel.setOnTouchListener((v, e) -> { detector.onTouchEvent(e); return false; });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Wearable.getMessageClient(this).addListener(this);
        linked = false;
        setStatus("連線中…", false);
        // 要一份目前比分，順便確認手機端有在聽
        sendCmd("hello", 0);
        handler.postDelayed(ackTimeout, ACK_TIMEOUT_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Wearable.getMessageClient(this).removeListener(this);
        handler.removeCallbacks(ackTimeout);
        handler.removeCallbacks(timerTick);
        handler.removeCallbacks(disarmReset);
    }

    // ── 送指令給手機 ──────────────────────────────────────────

    private void sendCmd(String cmd, int vibrateMs) {
        if (vibrateMs > 0) vibrate(vibrateMs);
        final byte[] payload = cmd.getBytes(StandardCharsets.UTF_8);

        Wearable.getNodeClient(this).getConnectedNodes()
            .addOnSuccessListener(nodes -> {
                android.util.Log.d("WEAR", "sendCmd " + cmd + " -> " + (nodes == null ? 0 : nodes.size()) + " node(s)");
                if (nodes == null || nodes.isEmpty()) {
                    linked = false;
                    setStatus("找不到已配對手機", false);
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
                setStatus("藍牙未連線", false);
            });
    }

    // ── 收手機回推的比分 ──────────────────────────────────────

    @Override
    public void onMessageReceived(MessageEvent event) {
        if (!PATH_STATE.equals(event.getPath())) return;
        final String json = new String(event.getData(), StandardCharsets.UTF_8);
        android.util.Log.d("WEAR", "收到狀態 " + json.length() + " bytes");
        runOnUiThread(() -> {
            handler.removeCallbacks(ackTimeout);
            linked = true;
            setStatus("已連線", true);
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
            tvStatus.setText(String.format(Locale.US, "● 第%d局  %d:%d",
                o.optInt("currentSet", 1), o.optInt("setsA", 0), o.optInt("setsB", 0)));
            tvStatus.setTextColor(0xFF4CAF50);

            timerRunning  = o.optBoolean("timerRunning", false);
            timerBaseMs   = o.optLong("timerMs", 0L);
            timerSyncedAt = SystemClock.elapsedRealtime();
            btnTimer.setText(timerRunning ? "❚❚" : "▶");

            handler.removeCallbacks(timerTick);
            renderTimer();
            if (timerRunning) handler.postDelayed(timerTick, 500L);
        } catch (Exception e) {
            // 狀態格式不對就維持畫面現狀，不要讓遙控器整個掛掉
        }
    }

    private void renderTimer() {
        long ms = timerRunning
            ? timerBaseMs + (SystemClock.elapsedRealtime() - timerSyncedAt)
            : timerBaseMs;
        long total = ms / 1000L;
        tvTimer.setText(String.format(Locale.US, "%02d:%02d", total / 60, total % 60));
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
