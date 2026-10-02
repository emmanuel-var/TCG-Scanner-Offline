# Play Store checklist

- [ ] Create an upload key and `keystore.properties`; enrol in Play App Signing.
- [ ] `./gradlew bundleRelease` → upload the `.aab`.
- [ ] Target API: 36 (already set). Re-check Google's yearly target-API deadline.
- [ ] Data safety form: *no data collected, no data shared*. Network calls only fetch public card data; camera is processed on device; nearby trading is user-initiated device-to-device.
- [ ] Privacy policy: publish `docs/PRIVACY_POLICY.md` at a public URL (add your contact e-mail) and enter it in the console; the in-app copy is under Settings → About and privacy.
- [ ] Permissions declarations: Camera (card scanning), Nearby devices / Bluetooth (P2P trading). No background location, no foreground services, no `MANAGE_EXTERNAL_STORAGE`.
- [ ] Content rating questionnaire (everyone; no UGC, no ads).
- [ ] Store listing: state clearly that the app is unofficial and not affiliated with any publisher; do not use publisher logos or trademarks in the icon, feature graphic or screenshots.
- [ ] Screenshots: phone + 7"/10" tablet.
- [ ] Pre-launch report: test on real devices (camera, Nearby needs Google Play Services).
- [ ] Review third-party licences (ML Kit, Nearby, CameraX, Room, Coil, OkHttp, kotlinx.serialization, LiteRT).
