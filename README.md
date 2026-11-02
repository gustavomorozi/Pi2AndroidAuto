# Morozi Auto Phone

An independent, from-scratch Android implementation of the "phone" side of the
Android Auto wired protocol — the half of the protocol that, as far as we could
find, no open-source project had published before (every existing open
implementation, including [Open Headunit](https://github.com/andreknieriem/open-headunit),
plays the "car"/head-unit side instead).

## Why

This is part of a larger reverse-engineering project studying how commercial
Android Auto/CarPlay dongles work. We confirmed the wired protocol itself
(Google's, not any vendor's) works correctly by pairing a real such dongle
with Open Headunit acting as the car — see the write-up for that in the
companion `carlinkit-lite-s` notes. This project is the next step: a Raspberry
Pi running Android, acting as the "phone," generating its own content instead
of relaying a real device's screen.

## Hardware / OS this targets

Developed and tested against a **Raspberry Pi 5** running
[KonstaKang's unofficial LineageOS 24.0 Android TV build](https://konstakang.com/devices/rpi5/LineageOS24-ATV/)
(Android 17). That build already has working USB-C gadget mode (ADB/MTP/PTP/tethering
confirmed), which is what this app needs to be able to act as a USB accessory when
plugged into a car — no kernel/driver changes required on top of it.

Should work on any Android device with a USB port that supports gadget/accessory
mode; the Pi 5 + KonstaKang image is just what this project happens to use.

## Status

Early. Implemented so far:
- USB accessory registration (`AndroidManifest.xml` + `accessory_filter.xml`)
- AAP frame read/write (`AapFrame.kt`)
- Version handshake (`AapSession.kt`)
- TLS handshake over the control channel via `SSLEngine` (`TlsChannel.kt`)

Not implemented yet: auth-complete, service discovery, channel setup, video
encoding/streaming, input handling. See the TODO block at the bottom of
`AapSession.run()`.

## Provenance

Written from scratch. The protocol's general shape (frames, control channel,
TLS, service discovery) is public knowledge shared by every independent open
implementation; nothing here was copied from any vendor's reverse-engineered
code. See the project notes for the explicit rule we're following on this.

## Building

Standard Gradle Android project.

```
./gradlew assembleDebug
```

## Installing

No release APK yet — this only gets through the TLS handshake so far, not
useful as an installable app on its own. Once it can actually drive a session
(service discovery + video working), a built APK and plain install
instructions (`adb install`, or sideloading via USB storage/file manager on
the device) will be added here.

## License

TBD.
