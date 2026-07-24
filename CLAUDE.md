# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

An Android library (`:androidCAN`) plus a two-device interop test app (`:app`)
for USB-CAN adapters via the Android USB Host API. One `CanDevice` interface,
two protocol drivers — each ported from its Linux kernel counterpart
(candleLight/gs_usb from `drivers/net/can/usb/gs_usb.c`, usb_8dev from
`drivers/net/can/usb/usb_8dev.c` in [torvalds/linux](https://github.com/torvalds/linux)).
Those two files are GPL-2.0-licensed kernel source, not part of this
permissively-licensed repo — they're referenced here as the protocol's origin,
not vendored. See [README.md](README.md) for the user-facing overview.

## Layout

- `androidCAN/` — library module, namespace `com.androidcan`
  - `com.androidcan` — common API: `CanDevice`, `CanFrame`, `ReceivedFrame`,
    `FrameListener`, `CanDeviceFactory`, `CanDeviceInfo`, `BitTiming` (shared
    bit-timing segments — mirrors the kernel's generic `struct can_bittiming`,
    which both drivers configure their hardware from)
  - `com.androidcan.gsusb` — gs_usb driver: `GsUsbDevice`, `GsUsb` (constants)
  - `com.androidcan.usb8dev` — usb_8dev driver: `Usb8DevDevice`, `Usb8Dev`
    (constants)
- `app/` — two-device test harness, package `com.candlelight.looptest`
  (`MainActivity`): pick a device for slot A and slot B, Start Test runs
  bidirectional TX/RX, a bulk burst, an idle-bus reconfigure of B, a
  bitrate-mismatch error check and a remote-frame (RTR) check between them.
  Results go to the UI and to logcat under tag `CanTest`.
- Gradle: version catalog in `gradle/libs.versions.toml`; `:app` depends on
  `:androidCAN`.

## Conventions

- **Both drivers implement `CanDevice` and must behave consistently.** When you
  change lifecycle or robustness behavior in one driver, mirror it in the other.
  Already in place in both: leak-safe `open()` (release the interface on any
  failure), idempotent RX-thread teardown, and an RX loop that survives a
  throwing `FrameListener`.
- **Bus-off recovery.** Both drivers auto-recover from bus-off, debounced ~1 s
  (analogous to the kernel's `restart-ms`, which neither driver actually
  implements upstream — see below): `Usb8DevDevice` re-issues its OPEN command
  on `STATUSMSG_BUSOFF`; `GsUsbDevice` detects `CAN_ERR_BUSOFF` (`0x40`) in a
  gs_usb error frame's id and re-runs BITTIMING + MODE_START. The debounce
  lives in `AbstractUsbCanDevice.maybeRestartAfterBusOff()`; each driver only
  supplies `restartAfterBusOff()`.
- Protocol constants live in a dedicated class per driver (`GsUsb`, `Usb8Dev`),
  named after the source definitions. Keep multi-byte wire order explicit:
  **gs_usb is little-endian, usb_8dev is big-endian.**
- Both drivers are ports of their Linux kernel counterparts — candleLight/gs_usb
  from `drivers/net/can/usb/gs_usb.c`, usb_8dev from
  `drivers/net/can/usb/usb_8dev.c` (both in torvalds/linux, GPL-2.0, not
  vendored in this repo). Cross-check any protocol change against the
  matching kernel source (control request numbers, struct layouts,
  wValue/wIndex conventions — gs_usb's per-channel requests use
  `wValue=channel, wIndex=0`; only HOST_FORMAT/DEVICE_CONFIG address
  `wIndex=bInterfaceNumber`). Korlan exposes four bulk endpoints: data RX/TX +
  command RX/TX. Note gs_usb itself has no kernel-side bus-off auto-restart
  (no `do_set_mode` callback) — the recovery above is this library's own
  addition, not a straight port.
- gs_usb signals errors via `CAN_ERR_FLAG` frames; usb_8dev sends type-3 status
  frames (`STATUS_FRAME` mode is enabled). Both are filtered out of the data
  path; both should drive bus-off recovery.
- **`GsUsbDevice` asks the device for its CAN clock, never assumes one.**
  `BT_CONST` (request 4) reports `fclk_can` plus the tseg/brp limits, and the
  bit timing is solved from those like the kernel's `can_calc_bittiming()`. A
  fixed table cannot work: candleLight on an STM32F042 clocks the controller at
  48 MHz, but other boards run 64, 80 or 160 MHz, and the same `brp` then means
  a *different bitrate* rather than an error. Measured on a CANable reporting
  160 MHz, where the old 48 MHz table drove a 500 kbit/s bus at 1666 kbit/s -
  form/stuff errors with `rec` pinned at 127 and not one frame received.
- **`applyChannelConfig()` sends `MODE_RESET` before `BITTIMING`.** Bit timing
  only reaches the controller's registers while it is in configuration mode, so
  writing it to a channel that is still running - an adapter left open by a
  killed process, or by an app replaced while its service ran - is silently
  dropped, and the old rate keeps running. The reset also clears the error
  counters, which is what lets a controller stuck error-passive after a bitrate
  mismatch come back without a physical replug. `restartAfterBusOff()` goes
  through the same method, so bus-off recovery gets the reset too.
- **`Usb8DevDevice.stop()` sends `CMD_CLOSE` before tearing down the RX thread,
  and the order matters.** A controller in error-passive emits status messages
  by the thousand per second; with nothing draining the data endpoint the
  firmware stops answering on its command endpoint, `CMD_CLOSE` times out, and
  the adapter is left open — after which even `open()`'s `CMD_RESET` fails and
  only a physical replug clears it. Measured on a Korlan: the close completes in
  ~500 ms through a flood of ~7000 status messages when the pump is still
  running, and times out at 1000 ms when it is not.

## Build & compile-check

```
./gradlew :app:assembleDebug                       # app + library
./gradlew :androidCAN:compileDebugJavaWithJavac    # library only
```

Needs `local.properties` with `sdk.dir`. Min SDK 26 (both app and library —
required by `AsyncBulkPump`'s use of `UsbRequest.queue(ByteBuffer)` and
`UsbDeviceConnection.requestWait(long)`, both API 26+), Java 11.

## Supported adapters

- candleLight/gs_usb family: `GsUsb.SUPPORTED_DEVICES` (e.g. `1d50:606f`).
- 8devices Korlan USB2CAN: `0483:1234` (`Usb8Dev.SUPPORTED_DEVICES`).

Keep `app/src/main/res/xml/device_filter.xml` (decimal VID/PID) in sync with
those lists.

## On-device / emulator testing

- USB passthrough works on an **Android Automotive** system image, not plain
  phone images. Launch with:
  `emulator -avd <automotive_avd> -no-window -no-snapshot -usb-passthrough vendorid=0x<vid>,productid=0x<pid>`
- Non-root passthrough needs a udev rule on the host:
  `SUBSYSTEM=="usb", ATTR{idVendor}=="<vid>", ATTR{idProduct}=="<pid>", MODE="0666", GROUP="plugdev"`
- Passthrough is **launch-time only**. A device already bound to a host CAN
  driver (`gs_usb`/`usb_8dev`) is detached when QEMU grabs it, and may not
  auto-rebind on release — replug to restore the host `canX` interface.
- Verify against a host peer with `can-utils`
  (`ip link set canX type can bitrate 500000`, `cansend`, `candump`).
- The scrolling log sits below the frame-count fields and the soft keyboard
  can cover it — dismiss the keyboard with its hide key (**not** BACK, which
  finishes the activity) before reading it via screenshot.
- Testing the app end-to-end needs **two** attached adapters wired to the same
  bus (or to each other) — a single device can't exercise the interop tests.

## Git

Standalone repo (no remote by default). Ask before committing.
