package com.twsa.scoreboard.wear;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
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

    private TextView tvStatus, tvNameA, tvNameB, tvScoreA, tvScoreB, tvTimer, btnTimer;
    private View panelA, panelB, btnReset;

    private Vibrator vibrator;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 是否曾經收到手機回應（收到才算真的接上計分板 App） */
    private boolean linked = false;

    // 本機計時器顯示：以手機送來的基準值 + 自行推算的經過時間
    private boolean timerRunning = false;
    private long timerBaseMs = 0L;
    private long timerSyncedAt = 0L;

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

        // 點一下 +1，長按 −1
        panelA.setOnClickListener(v -> sendCmd("scoreA", 30));
        panelB.setOnClickListener(v -> sendCmd("scoreB", 30));
        panelA.setOnLongClickListener(v -> { sendCmd("unscoreA", 60); return true; });
        panelB.setOnLongClickListener(v -> { sendCmd("unscoreB", 60); return true; });

        btnTimer.setOnClickListener(v -> sendCmd("timer", 30));

        // 重置只認長按，避免比賽中誤觸把分數清掉
        btnReset.setOnClickListener(v ->
            Toast.makeText(this, "長按重置比賽", Toast.LENGTH_SHORT).show());
        btnReset.setOnLongClickListener(v -> { sendCmd("resetConfirmed", 120); return true; });
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
    }

    // ── 送指令給手機 ──────────────────────────────────────────

    private void sendCmd(String cmd, int vibrateMs) {
        if (vibrateMs > 0) vibrate(vibrateMs);
        final byte[] payload = cmd.getBytes(StandardCharsets.UTF_8);

        Wearable.getNodeClient(this).getConnectedNodes()
            .addOnSuccessListener(nodes -> {
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
