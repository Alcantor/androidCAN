package com.androidcan;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbRequest;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeoutException;

/**
 * Keeps several reads concurrently in flight on one bulk IN endpoint, using
 * Android's async {@link UsbRequest} queue instead of one synchronous
 * bulkTransfer() at a time.
 *
 * <p>The kernel drivers these two Android drivers are ported from submit a
 * pool of URBs up front and resubmit each as it completes (usb_8dev.c:
 * {@code MAX_RX_URBS = 20}), so the device never has a moment with no
 * pending read. A single blocking bulkTransfer() call in a loop has no such
 * pipeline: under a fast back-to-back burst, the gap between one read
 * completing and the next being posted can exceed the frame arrival rate —
 * and unlike a real bulk endpoint's NAK-based backpressure, that gap is
 * where an imperfect host USB stack (observed both under emulator USB
 * passthrough and on real hardware, and separately documented as a
 * long-standing reliability issue with {@code bulkTransfer()} itself)
 * silently drops data instead of stalling for it. Keeping
 * {@link #REQUEST_DEPTH} reads always outstanding closes that gap.</p>
 *
 * <p>Requires API 26, for {@link UsbRequest#queue(ByteBuffer)} (reports the
 * transferred length via the buffer position) and the timeout overload of
 * {@link UsbDeviceConnection#requestWait(long)}. Callers on lower API
 * levels must fall back to a synchronous bulkTransfer() loop.</p>
 *
 * <p><b>Lifecycle note:</b> {@link #close} must drain every request's
 * completion — including the cancellation completion {@link UsbRequest#cancel()}
 * generates — before calling {@link UsbRequest#close()} on it. Closing a
 * request while a completion for it is still unreaped is a documented
 * Android footgun: a later {@link UsbDeviceConnection#requestWait} call can
 * still hand back that same (now-closed) request, and its native
 * {@code dequeue()} dereferences a field close() already nulled out,
 * crashing with a {@code NullPointerException} in {@code UsbEndpoint.getDirection()}
 * from inside {@code libusbhost}. This class was rewritten once already
 * after hitting exactly that crash from skipping the drain step.</p>
 */
public final class AsyncBulkPump {

    private static final int REQUEST_DEPTH = 8;
    private static final int DRAIN_TIMEOUT_MS = 1000;

    private final UsbDeviceConnection connection;
    private final UsbRequest[] requests = new UsbRequest[REQUEST_DEPTH];
    private final ByteBuffer[] buffers = new ByteBuffer[REQUEST_DEPTH];
    private volatile boolean closed;

    public AsyncBulkPump(UsbDeviceConnection connection, UsbEndpoint endpoint, int transferSize) {
        this.connection = connection;
        for (int i = 0; i < REQUEST_DEPTH; i++) {
            UsbRequest request = new UsbRequest();
            if (!request.initialize(connection, endpoint)) {
                throw new IllegalStateException(
                        "Failed to initialize UsbRequest on endpoint " + endpoint.getAddress());
            }
            requests[i] = request;
            buffers[i] = ByteBuffer.allocateDirect(transferSize);
        }
    }

    /** Queue every request. Call once, before the first {@link #poll}. */
    public void start() {
        for (int i = 0; i < REQUEST_DEPTH; i++) {
            requeue(requests[i], buffers[i]);
        }
    }

    private static void requeue(UsbRequest request, ByteBuffer buffer) {
        buffer.clear();
        request.queue(buffer);
    }

    /**
     * Block for the next completed read, return its bytes, and immediately
     * re-queue the same request so {@link #REQUEST_DEPTH} stays constantly
     * in flight. Returns null on timeout or after {@link #close} — callers
     * should just check their own running flag and call again.
     */
    public byte[] poll(int timeoutMs) {
        UsbRequest completed;
        try {
            completed = connection.requestWait(timeoutMs);
        } catch (TimeoutException e) {
            return null;
        }
        if (completed == null) return null;

        int index = indexOf(completed);
        if (index < 0) return null; // not ours (shouldn't happen — one pump per endpoint at a time)

        ByteBuffer buffer = buffers[index];
        buffer.flip();
        byte[] out = new byte[buffer.remaining()];
        buffer.get(out);

        if (!closed) {
            requeue(completed, buffer);
        }
        return out;
    }

    private int indexOf(UsbRequest request) {
        for (int i = 0; i < REQUEST_DEPTH; i++) {
            if (requests[i] == request) return i;
        }
        return -1;
    }

    /**
     * Cancel every outstanding request, drain the connection's completion
     * queue until each one (including its cancellation completion) has been
     * reaped, then close them. Must be called from the same thread whose
     * {@link #poll} loop just exited — nothing else may call {@link #poll}
     * concurrently during the drain.
     */
    public void close() {
        closed = true;
        for (UsbRequest request : requests) {
            request.cancel();
        }

        boolean[] reaped = new boolean[REQUEST_DEPTH];
        int remaining = REQUEST_DEPTH;
        long deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS;
        while (remaining > 0 && System.currentTimeMillis() < deadline) {
            UsbRequest completed;
            try {
                completed = connection.requestWait(100);
            } catch (TimeoutException e) {
                continue;
            }
            if (completed == null) continue;
            int index = indexOf(completed);
            if (index >= 0 && !reaped[index]) {
                reaped[index] = true;
                remaining--;
            }
        }
        // Any request not reaped within the deadline is leaked rather than
        // closed — closing it now would reintroduce the crash this drain
        // exists to prevent. Should not happen in practice: cancel()
        // guarantees a completion event, and DRAIN_TIMEOUT_MS is generous.

        for (int i = 0; i < REQUEST_DEPTH; i++) {
            if (reaped[i]) {
                requests[i].close();
            }
        }
    }
}
