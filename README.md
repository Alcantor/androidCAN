# androidCAN

An Android library for talking to USB-CAN adapters through the Android USB Host
API, plus a two-device interop test app. It exposes a single `CanDevice`
interface backed by two protocol drivers, each ported from its Linux kernel
counterpart:

- **candleLight / gs_usb** — Geschwister Schneider and compatible firmware
  (CANable, CANtact, CES CANext FD, …), ported from
  `drivers/net/can/usb/gs_usb.c` in
  [torvalds/linux](https://github.com/torvalds/linux).
- **8devices USB2CAN ("Korlan") / usb_8dev** — ported from
  `drivers/net/can/usb/usb_8dev.c` in the same tree.

Those two kernel files are GPL-2.0-licensed and not vendored in this repo;
they're referenced above as the protocol's origin, not shipped alongside it.

## Modules

| Module | Description |
|--------|-------------|
| `:androidCAN` | The library. Namespace `com.androidcan`. |
| `:app` | Test app: pick two attached adapters and run an interop test between them. |

## Library structure

```
com.androidcan                  common API
  CanDevice                     driver-agnostic interface (open/start/stop/send/close)
  CanFrame, ReceivedFrame       classic CAN frame value types
  FrameListener                 RX callback
  CanDeviceFactory              enumerate() / isSupported() / create()
  CanDeviceInfo                 attached device + driver label
  BitTiming                     shared CAN bit-timing segments (both drivers)
com.androidcan.gsusb            gs_usb driver
  GsUsbDevice, GsUsb
com.androidcan.usb8dev          usb_8dev driver
  Usb8DevDevice, Usb8Dev
```

## Usage

```java
UsbManager mgr = (UsbManager) getSystemService(Context.USB_SERVICE);

// Discover attached, supported adapters.
List<CanDeviceInfo> found = CanDeviceFactory.enumerate(mgr);

// Build a driver for one of them (after obtaining USB permission).
CanDevice dev = CanDeviceFactory.create(mgr, found.get(0).device);
dev.open();
dev.addFrameListener(rx ->
    Log.i("CAN", String.format("id=%X dlc=%d", rx.frame.id, rx.frame.data.length)));
dev.start(500000);                       // go on-bus at 500 kbit/s

dev.send(new CanFrame(0x123, false, false, new byte[]{1, 2, 3, 4}));

dev.stop();
dev.close();
```

candleLight/gs_usb reports its actual CAN clock via `BT_CONST` (it varies by
board - 48 MHz on the STM32F042-based CANable, 64/80/160 MHz on others), and
the driver solves the bit-timing registers from that clock like the kernel's
`can_calc_bittiming()`, so any bitrate the controller can represent is
supported, not just a fixed preset list. usb_8dev instead derives its
prescaler from a 32 MHz clock for any bitrate that divides evenly.

## Building

```
./gradlew :app:assembleDebug
```

Requires an Android SDK — set `sdk.dir` in `local.properties`. Min SDK 26
(app and library); Java 11.

## Test app

Attach two supported CAN adapters wired to the same bus (or to each other),
tap **Refresh devices**, pick one for **Device A** and a different one for
**Device B**, then **Start Test**. It runs, in order:

1. A → B and B → A: send a run of sequence-numbered frames each way, verify
   every one arrives.
2. Bulk: send a larger burst back-to-back and report loss and throughput.
3. Reconfigure: stop and restart B at the bitrate it is already using, with
   nothing on the bus, then check it still passes traffic. Separates a plain
   stop/start from the same thing done while the bus is in error, which is what
   the next step does — the two failing differently is what localises a fault.
4. Bitrate mismatch: reconfigure B alone at a different bitrate (default
   1 Mbit/s vs. A's 500 kbit/s) and confirm A's frames are *not* accepted —
   then restore B and re-run the A → B check to confirm recovery.
5. Remote frames, one each way (standard id, then extended): confirm the RTR
   flag survives the trip and that the received frame reports an empty payload,
   since a remote frame puts no data on the wire. Nothing answers the request —
   neither adapter does automatic remote response — so the arriving frame is
   the whole expected result.

Frame/bulk counts and the mismatch bitrate are fixed constants (not
user-editable), so runs are reproducible and comparable. Results show in the
summary panel and the scrolling log, and are also written
to logcat under tag `CanTest` (driver-internal errors log under each driver's
own class-name tag). `res/xml/device_filter.xml` auto-launches the app on
attach for the supported vendor/product IDs.

## Hardware / testing notes

Real USB-CAN testing on an emulator needs an image that supports USB
passthrough (Android Automotive works; plain phone images generally do not),
launched with `-usb-passthrough vendorid=0x…,productid=0x…`, plus a udev rule
granting non-root access to the device. See [CLAUDE.md](CLAUDE.md) for details.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
