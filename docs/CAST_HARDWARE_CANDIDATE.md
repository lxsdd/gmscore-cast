# GmsCore Cast — first hardware-candidate gate

## Qualification boundaries

- Source baseline: `lxsdd/gmscore-cast` on protected `main`, with required
  `Cast core + framework (software only)` check passing. Exact commit hash is
  recorded in every candidate's PROVENANCE.txt.
- This fork's CI has no signing credentials. The packaging workflow creates
  one **unsigned** `app.revanced.android.gms` release APK and publishes a
  short-lived artifact with SHA-256. This is **not** a signed installable APK,
  not a release and **not** proof that Cast works on physical devices.
- The candidate workflow is deliberately restricted: its *initial addition*
  to `main` triggers one full-application packaging build. Subsequent ordinary
  commits and PRs do not trigger it. Re-runs are manual and should occur only
  for a new, intentionally approved hardware iteration.
- No full APK build is done by the FASTDEV inner loop.

## Signing and update safety: required before the first install

1. On the intended **test phone**, identify the actually installed GmsCore
   package ID and signing certificate digest. Do not infer the certificate
   from the project name, package name, APK filename or this repository.
2. Verify the candidate source SHA and APK SHA-256 from PROVENANCE.txt. Ensure
   you trust the exact source version and understand that GitHub has only
   qualified packaging, not hardware behavior.
3. Sign the unsigned candidate **offline using the same signing certificate**
   as the version already installed on that phone, using a secure existing
   signing workflow. Do not upload a private keystore, password or signed
   private artifact to this public repository or GitHub Actions.
4. Confirm the signed candidate's certificate digest is identical to the
   currently installed package **before** using `adb install -r`. Android
   rejects ordinary updates when signatures differ. Do not uninstall the
   currently working GmsCore merely to bypass this protection.
5. If the existing signing key cannot be used, STOP. A separately authorized,
   isolated test-device strategy is needed. Do not alter a clean device.

## First minimal hardware pass (private evidence)

Record the source SHA and yes/no results. Keep receiver names, route IDs,
IP addresses, network logs, private APKs and device identifiers out of the
public repo. No changes to any Cast receiver are required.

1. After installing a compatible candidate, confirm normal GmsCore startup,
   the existing Google Play Services coexistence, and Morphe-YouTube's
   GmsCore connection without breaking previous playback.
2. Open the Cast chooser: check TV plus individual speakers, pairs and groups
   are offered without being misclassified. Reopen chooser in same session
   and check *the same categories and devices* remain.
3. Select one single speaker, a paired route, a speaker group, and one TV.
   Capture selected route and connection state, playing status, volume/mute
   feedback and disconnection/reselection behavior.
4. Test a real audio application and Morphe-YouTube individually: distinguish
   CAST service ready, LAUNCH success, actual receiver LOAD acceptance,
   MEDIA_STATUS/PLAYING, audible audio, NEXT and resume. Client success or a
   spinning UI alone is not proof of audio playback.
5. Test network interruption, chooser reopen, disconnect/reconnect, device
   standby, and audio/video disconnection parity. Never equate synthetic
   JUnit success with this practical acceptance.

### Important known functional unknowns

- The receiver may reject the client-configured YouTube DMR receiver/application
  launch even when route discovery and CastV2 connection are correct. The
  previous private research observed a receiver failure, but no private log,
  receiver ID or source is reproduced here.
- `SET_MUTED` and `TOGGLE_MUTED` must be confirmed end-to-end with real
  receiver volume state and stale-controller protection.
- GmsCore framework registration and package compatibility require a real
  Android installation to validate. Do not declare hardware PASS from CI.

## Failure handling

Preserve the exact Git SHA, source artifact SHA-256 and minimal non-identifying
reproduction steps. Do not push hardware logs to the public repository.
Fix failures in a new PR, qualify via FASTDEV, then schedule **at most one**
real APK packaging run per subsequent hardware iteration.
