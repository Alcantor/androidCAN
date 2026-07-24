package com.candlelight.looptest;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.androidcan.CanDevice;
import com.androidcan.CanDeviceFactory;
import com.androidcan.CanDeviceInfo;
import com.androidcan.CanFrame;
import com.androidcan.FrameListener;
import com.androidcan.ReceivedFrame;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Two-device interop test harness: pick a CAN adapter for slot A and one for
 * slot B, then Start Test runs bidirectional transmission, a bulk burst, and
 * a bitrate-mismatch error-behavior check between them.
 */
public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "com.candlelight.looptest.USB_PERMISSION";
    private static final String TAG = "CanTest";
    private static final int GOOD_BITRATE = 500000;
    private static final int MAX_LOG_LINES = 500;
    private static final long LOG_FLUSH_MS = 100;

    // Fixed test parameters, kept constant (not user-editable) so results are
    // reproducible and comparable across runs.
    private static final int TEST_FRAMES = 20;
    private static final int BULK_FRAMES = 200;
    private static final int MISMATCH_BITRATE = 1000000;

    private UsbManager usbManager;
    private final List<CanDeviceInfo> availableDevices = new ArrayList<>();
    private ArrayAdapter<CanDeviceInfo> adapterA;
    private ArrayAdapter<CanDeviceInfo> adapterB;

    /** Permission grants are requested one at a time (A then B), so a device-keyed map is enough. */
    private final Map<UsbDevice, Runnable> pendingGrants = new ConcurrentHashMap<>();

    private volatile boolean testRunning;
    private final Slot slotA = new Slot("A");
    private final Slot slotB = new Slot("B");

    // Frames/log lines arrive on driver RX threads and can burst far faster
    // than the UI can redraw, so they are queued and drained by a single
    // coalesced flush. See the ANR fix this replaced: posting one UI message
    // per line saturates the main thread under bulk-test load.
    private final ConcurrentLinkedQueue<String> pendingLines = new ConcurrentLinkedQueue<>();
    private final ArrayDeque<String> logLines = new ArrayDeque<>();   // UI thread only
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private TextView tvStatus;
    private TextView tvTestSummary;
    private Button btnScan;
    private Button btnStartTest;
    private Spinner spinnerDeviceA;
    private Spinner spinnerDeviceB;
    private ScrollView scrollLog;
    private TextView tvLog;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                Runnable onGranted = usbDevice != null ? pendingGrants.remove(usbDevice) : null;
                if (granted && onGranted != null) {
                    onGranted.run();
                } else if (!granted) {
                    Toast.makeText(MainActivity.this, "USB permission denied", Toast.LENGTH_SHORT).show();
                    abortTest("USB permission denied");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                refreshDeviceList();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (usbDevice != null && (usbDevice.equals(slotA.usbDevice) || usbDevice.equals(slotB.usbDevice))) {
                    Toast.makeText(MainActivity.this, "A test device was detached", Toast.LENGTH_SHORT).show();
                }
                refreshDeviceList();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvTestSummary = findViewById(R.id.tvTestSummary);
        btnScan = findViewById(R.id.btnScan);
        btnStartTest = findViewById(R.id.btnStartTest);
        spinnerDeviceA = findViewById(R.id.spinnerDeviceA);
        spinnerDeviceB = findViewById(R.id.spinnerDeviceB);
        scrollLog = findViewById(R.id.scrollLog);
        tvLog = findViewById(R.id.tvLog);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        adapterA = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, availableDevices);
        adapterA.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDeviceA.setAdapter(adapterA);

        adapterB = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, availableDevices);
        adapterB.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDeviceB.setAdapter(adapterB);

        btnScan.setOnClickListener(v -> refreshDeviceList());
        btnStartTest.setOnClickListener(v -> onStartTestClicked());
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        refreshDeviceList();
        recheckPendingGrants();
    }

    /**
     * The system permission dialog runs in its own activity, which pauses
     * MainActivity — and {@link #onPause} unregisters {@link #usbReceiver}.
     * If the grant broadcast lands in that window it's dropped for good
     * (normal broadcasts aren't queued for a receiver that registers later),
     * even though the OS permission table is already updated. Re-checking
     * every pending grant here, right as we resume from that dialog, closes
     * the gap without depending on the broadcast having been delivered.
     */
    private void recheckPendingGrants() {
        for (UsbDevice device : new ArrayList<>(pendingGrants.keySet())) {
            if (usbManager.hasPermission(device)) {
                Runnable onGranted = pendingGrants.remove(device);
                if (onGranted != null) onGranted.run();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(usbReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacksAndMessages(null);
        closeSlot(slotA);
        closeSlot(slotB);
    }

    private void refreshDeviceList() {
        List<CanDeviceInfo> found = CanDeviceFactory.enumerate(usbManager);
        availableDevices.clear();
        availableDevices.addAll(found);
        adapterA.notifyDataSetChanged();
        adapterB.notifyDataSetChanged();
    }

    private void onStartTestClicked() {
        if (testRunning) return;

        CanDeviceInfo infoA = (CanDeviceInfo) spinnerDeviceA.getSelectedItem();
        CanDeviceInfo infoB = (CanDeviceInfo) spinnerDeviceB.getSelectedItem();
        if (infoA == null || infoB == null) {
            Toast.makeText(this, "Select a device for both A and B", Toast.LENGTH_SHORT).show();
            return;
        }
        if (infoA == infoB) {
            Toast.makeText(this, "Pick two different devices", Toast.LENGTH_SHORT).show();
            return;
        }

        testRunning = true;
        btnStartTest.setEnabled(false);
        tvTestSummary.setText("");
        tvStatus.setText("Requesting permission…");

        UsbDevice usbA = infoA.device;
        UsbDevice usbB = infoB.device;
        ensurePermission(usbA, () -> ensurePermission(usbB, () -> runTestSuite(usbA, usbB)));
    }

    private void ensurePermission(UsbDevice usbDevice, Runnable onGranted) {
        if (usbManager.hasPermission(usbDevice)) {
            onGranted.run();
            return;
        }
        pendingGrants.put(usbDevice, onGranted);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), flags);
        usbManager.requestPermission(usbDevice, pi);
    }

    private void abortTest(String reason) {
        testRunning = false;
        runOnUiThread(() -> {
            btnStartTest.setEnabled(true);
            tvStatus.setText("Aborted: " + reason);
        });
        closeSlot(slotA);
        closeSlot(slotB);
    }

    /** Runs the whole suite on a background thread; USB control/bulk transfers block the caller. */
    private void runTestSuite(UsbDevice usbA, UsbDevice usbB) {
        new Thread(() -> {
            List<String> lines = new ArrayList<>();
            boolean overallPass = true;
            slotA.usbDevice = usbA;
            slotB.usbDevice = usbB;
            try {
                openSlot(slotA, usbA);
                openSlot(slotB, usbB);

                slotA.canDevice.start(GOOD_BITRATE);
                slotB.canDevice.start(GOOD_BITRATE);
                setStatus("Testing…");

                TestResult r1 = testDirection(slotA, slotB, 0x100, TEST_FRAMES);
                lines.add(r1.line("A -> B (" + TEST_FRAMES + " frames)"));
                overallPass &= r1.pass;

                TestResult r2 = testDirection(slotB, slotA, 0x200, TEST_FRAMES);
                lines.add(r2.line("B -> A (" + TEST_FRAMES + " frames)"));
                overallPass &= r2.pass;

                TestResult r3 = testBulk(slotA, slotB, 0x300, BULK_FRAMES);
                lines.add(r3.line("Bulk A -> B (" + BULK_FRAMES + " frames)"));
                overallPass &= r3.pass;

                /* Reconfigure B on a quiet, healthy bus. The mismatch step does
                 * the same stop/start, but only after driving the bus into
                 * error - so this separates "this adapter cannot be
                 * reconfigured" from "it cannot be reconfigured while it is
                 * flooding status frames". */
                TestResult r3b = testReconfigure(slotA, slotB, 0x350, TEST_FRAMES);
                lines.add(r3b.line("Reconfigure B on an idle bus, then A -> B"));
                overallPass &= r3b.pass;

                TestResult r4 = testBitrateMismatch(slotA, slotB, 0x400, TEST_FRAMES, MISMATCH_BITRATE);
                lines.add(r4.line("Bitrate mismatch (B@" + MISMATCH_BITRATE + " vs A@" + GOOD_BITRATE + ")"));
                overallPass &= r4.pass;

                TestResult r5 = testDirection(slotA, slotB, 0x500, TEST_FRAMES);
                lines.add(r5.line("Recovery after mismatch (A -> B, " + TEST_FRAMES + " frames)"));
                overallPass &= r5.pass;

                TestResult r6 = testRemoteFrame(slotA, slotB, 0x600, false);
                lines.add(r6.line("Remote frame A -> B (standard id)"));
                overallPass &= r6.pass;

                TestResult r7 = testRemoteFrame(slotB, slotA, 0x18DA0601, true);
                lines.add(r7.line("Remote frame B -> A (extended id)"));
                overallPass &= r7.pass;
            } catch (IOException e) {
                log(Log.ERROR, "Test aborted: " + e.getMessage());
                lines.add("ABORTED: " + e.getMessage());
                overallPass = false;
            } finally {
                closeSlot(slotA);
                closeSlot(slotB);
                postSummary(buildSummary(lines, overallPass), overallPass);
            }
        }, "can-test").start();
    }

    private void openSlot(Slot slot, UsbDevice usbDevice) throws IOException {
        CanDevice dev = CanDeviceFactory.create(usbManager, usbDevice);
        if (dev == null) {
            throw new IOException("Unsupported device: " + usbDevice.getDeviceName());
        }
        dev.open();
        dev.addFrameListener(slot.listener());
        slot.canDevice = dev;
        log(Log.INFO, slot.label + " opened: " + dev.getDeviceInfo());
    }

    private void closeSlot(Slot slot) {
        if (slot.canDevice != null) {
            try {
                slot.canDevice.stop();
            } catch (IOException ignored) {
                // best effort; close() still releases the device
            }
            slot.canDevice.close();
            slot.canDevice = null;
        }
    }

    /** Send {@code count} sequence-numbered frames from sender to receiver and verify all arrive. */
    private TestResult testDirection(Slot sender, Slot receiver, int canId, int count) {
        receiver.received.clear();
        log(Log.INFO, sender.label + " -> " + receiver.label + ": sending " + count
                + " frames (id 0x" + Integer.toHexString(canId) + ")");
        try {
            sendSequence(sender, canId, count);
        } catch (IOException e) {
            return TestResult.fail("send failed: " + e.getMessage());
        }
        return waitAndVerify(receiver, canId, count);
    }

    /** Same as {@link #testDirection}, but sends the whole burst back-to-back and reports throughput. */
    private TestResult testBulk(Slot sender, Slot receiver, int canId, int count) {
        receiver.received.clear();
        log(Log.INFO, "Bulk " + sender.label + " -> " + receiver.label + ": sending " + count + " frames back-to-back");
        long start = System.nanoTime();
        try {
            sendSequence(sender, canId, count);
        } catch (IOException e) {
            return TestResult.fail("bulk send failed after " + countMatching(receiver, canId) + " frames acked: " + e.getMessage());
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        TestResult base = waitAndVerify(receiver, canId, count);
        double fps = elapsedMs > 0 ? count * 1000.0 / elapsedMs : 0;
        return new TestResult(base.pass, base.detail + String.format(", %d ms send time, %.0f fps", elapsedMs, fps));
    }

    /** Reconfigure the receiver at a mismatched bitrate and confirm it does NOT accept valid frames. */
    /**
     * Stop and restart the receiver at the bitrate it is already using, with
     * nothing on the bus, then check it still passes traffic. Isolates the
     * stop/start command exchange from the error conditions the mismatch step
     * creates.
     */
    private TestResult testReconfigure(Slot sender, Slot receiver, int canId, int count) {
        log(Log.INFO, "Reconfiguring " + receiver.label + " at " + GOOD_BITRATE + " on an idle bus");
        try {
            receiver.canDevice.stop();
            receiver.canDevice.start(GOOD_BITRATE);
        } catch (IOException e) {
            return TestResult.fail("stop/start failed: " + e.getMessage());
        }
        return testDirection(sender, receiver, canId, count);
    }

    private TestResult testBitrateMismatch(Slot sender, Slot receiver, int canId, int count, int mismatchBitrate) {
        try {
            receiver.canDevice.stop();
            receiver.canDevice.start(mismatchBitrate);
        } catch (IOException e) {
            return TestResult.fail("could not reconfigure " + receiver.label + " to " + mismatchBitrate + ": " + e.getMessage());
        }
        log(Log.INFO, receiver.label + " reconfigured at mismatched bitrate " + mismatchBitrate + " while "
                + sender.label + " stays at " + GOOD_BITRATE + " — sending " + count + " frames, expecting rejection");
        receiver.received.clear();
        try {
            sendSequence(sender, canId, count);
        } catch (IOException e) {
            log(Log.WARN, sender.label + " TX errored during mismatch test (plausible under bus errors): " + e.getMessage());
        }
        sleep(Math.min(2000, 300 + count * 5L));

        int gotValid = countMatching(receiver, canId);
        boolean pass = gotValid == 0;
        String detail = gotValid + "/" + count + " valid frames received while mismatched (0 expected — see "
                + receiver.label + "'s driver log tag for internal CAN error frames)";

        // Always try to restore the good bitrate so the recovery subtest that follows is meaningful.
        try {
            receiver.canDevice.stop();
            receiver.canDevice.start(GOOD_BITRATE);
            log(Log.INFO, receiver.label + " restored to " + GOOD_BITRATE);
        } catch (IOException e) {
            pass = false;
            detail += "; FAILED to restore " + receiver.label + " to " + GOOD_BITRATE + ": " + e.getMessage();
        }
        return new TestResult(pass, detail);
    }

    private void sendSequence(Slot sender, int canId, int count) throws IOException {
        for (int i = 0; i < count; i++) {
            byte[] data = ByteBuffer.allocate(4).putInt(i).array();
            sender.canDevice.send(new CanFrame(canId, false, false, data));
        }
    }

    /** Poll until every expected sequence number for {@code canId} shows up, or a timeout elapses. */
    /**
     * A remote frame puts only an id and a dlc on the wire - no payload. Check
     * the RTR flag survives the trip and that the receiver reports an empty
     * payload rather than whatever residue sat in the adapter's data field.
     *
     * <p>Nothing answers the request: neither adapter is configured for
     * automatic remote response, and this library never generates one. So the
     * single frame arriving at the far side is the whole expected outcome.</p>
     */
    private TestResult testRemoteFrame(Slot sender, Slot receiver, int canId, boolean extended) {
        receiver.received.clear();
        log(Log.INFO, sender.label + " -> " + receiver.label + ": remote frame (id 0x"
                + Integer.toHexString(canId) + (extended ? ", extended)" : ")"));
        try {
            sender.canDevice.send(new CanFrame(canId, extended, true, new byte[0]));
        } catch (IOException e) {
            return TestResult.fail("send failed: " + e.getMessage());
        }

        ReceivedFrame got = null;
        long deadline = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < deadline && got == null) {
            for (ReceivedFrame rf : receiver.received) {
                if (rf.frame.id == canId) {
                    got = rf;
                    break;
                }
            }
            if (got == null) sleep(20);
        }

        if (got == null) return TestResult.fail("not received");
        if (got.frame.isExtended != extended) {
            return TestResult.fail("received, but extended flag is " + got.frame.isExtended);
        }
        if (!got.frame.isRemote) return TestResult.fail("received, but the RTR flag was lost");
        if (got.frame.data.length != 0) {
            return TestResult.fail("RTR frame carried " + got.frame.data.length + " data bytes");
        }
        return new TestResult(true, "RTR flag set, no payload");
    }

    private TestResult waitAndVerify(Slot receiver, int canId, int expectedCount) {
        long deadline = System.currentTimeMillis() + 1000 + expectedCount * 5L;
        while (System.currentTimeMillis() < deadline && countMatching(receiver, canId) < expectedCount) {
            sleep(20);
        }

        BitSet seen = new BitSet(expectedCount);
        for (ReceivedFrame rf : receiver.received) {
            if (rf.frame.id == canId && rf.frame.data.length >= 4) {
                int seq = ByteBuffer.wrap(rf.frame.data).getInt();
                if (seq >= 0 && seq < expectedCount) seen.set(seq);
            }
        }
        int receivedValid = seen.cardinality();
        boolean pass = receivedValid == expectedCount;
        String detail = receivedValid + "/" + expectedCount + " received";
        if (!pass) {
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < expectedCount && missing.size() < 10; i++) {
                if (!seen.get(i)) missing.add(i);
            }
            detail += ", missing " + (expectedCount - receivedValid) + " e.g. " + missing;
        }
        return new TestResult(pass, detail);
    }

    private int countMatching(Slot slot, int canId) {
        int n = 0;
        for (ReceivedFrame rf : slot.received) {
            if (rf.frame.id == canId) n++;
        }
        return n;
    }

    private String buildSummary(List<String> lines, boolean overallPass) {
        StringBuilder sb = new StringBuilder("=== Test Summary ===\n");
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        sb.append("Overall: ").append(overallPass ? "PASS" : "FAIL");
        return sb.toString();
    }

    private void postSummary(String summary, boolean overallPass) {
        log(overallPass ? Log.INFO : Log.WARN, summary.replace('\n', ' '));
        runOnUiThread(() -> {
            tvTestSummary.setText(summary);
            tvStatus.setText(overallPass ? "Test PASSED" : "Test FAILED");
            btnStartTest.setEnabled(true);
            testRunning = false;
        });
    }

    private void setStatus(String status) {
        runOnUiThread(() -> tvStatus.setText(status));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Log to logcat (tag {@value #TAG}) and to the in-app scrolling log. */
    private void log(int level, String msg) {
        switch (level) {
            case Log.WARN:
                Log.w(TAG, msg);
                break;
            case Log.ERROR:
                Log.e(TAG, msg);
                break;
            default:
                Log.i(TAG, msg);
        }
        appendLog(msg);
    }

    private void appendLog(String line) {
        pendingLines.add(line);
        scheduleLogFlush();
    }

    /** Ask for a log refresh. Safe from any thread; at most one UI update per {@link #LOG_FLUSH_MS}. */
    private void scheduleLogFlush() {
        if (flushScheduled.compareAndSet(false, true)) {
            uiHandler.postDelayed(this::flushLog, LOG_FLUSH_MS);
        }
    }

    private void flushLog() {
        flushScheduled.set(false);

        boolean added = false;
        for (String line = pendingLines.poll(); line != null; line = pendingLines.poll()) {
            logLines.addLast(line);
            added = true;
        }
        if (!added) return;

        while (logLines.size() > MAX_LOG_LINES) {
            logLines.removeFirst();
        }

        StringBuilder sb = new StringBuilder();
        for (String line : logLines) {
            sb.append(line).append('\n');
        }
        tvLog.setText(sb.toString());
        scrollLog.post(() -> scrollLog.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private String formatFrame(int channel, CanFrame frame) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("CH%d #%03X [%d]", channel, frame.id, frame.data.length));
        for (byte b : frame.data) {
            sb.append(String.format(" %02X", b));
        }
        return sb.toString();
    }

    /** One side of the test: which device is in it, its driver, and what it has received. */
    private class Slot {
        final String label;
        volatile UsbDevice usbDevice;
        volatile CanDevice canDevice;
        final Queue<ReceivedFrame> received = new ConcurrentLinkedQueue<>();

        Slot(String label) {
            this.label = label;
        }

        FrameListener listener() {
            return rx -> {
                received.add(rx);
                appendLog(label + " RX " + formatFrame(rx.channel, rx.frame));
            };
        }
    }

    private static class TestResult {
        final boolean pass;
        final String detail;

        TestResult(boolean pass, String detail) {
            this.pass = pass;
            this.detail = detail;
        }

        static TestResult fail(String detail) {
            return new TestResult(false, detail);
        }

        String line(String name) {
            return (pass ? "PASS " : "FAIL ") + name + ": " + detail;
        }
    }
}
