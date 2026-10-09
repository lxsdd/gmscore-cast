# Public GmsCore Cast downstream — workflow and contribution policy

This repository is based on the **public Apache-2.0-licensed**
[MorpheApp/MicroG-RE](https://github.com/MorpheApp/MicroG-RE), itself a fork of
[microG/GmsCore](https://github.com/microg/GmsCore). Retain upstream copyright,
licenses and required attribution. It is an independent development fork, not
an official Morphe, ReVanced or microG release channel.

## Canonical development
- Use this repository for reviewed public GmsCore/Cast source and CI.
- Use `main` as the protected canonical branch and feature branches + PRs for changes.
- Check upstream differences before each port. Never replace newer upstream files
  wholesale with an older downstream tree.
- Import changes from private research only as reviewed source/test diffs,
  **never** by pushing a private repository's history, tags, mirrors or raw
  diagnostic archives.
- Public integration changes must build independently of anyone's hardware,
  music, route identifiers, private APKs, logcats, or account credentials.

## CI and verification
- `.github/workflows/build.yml` is deliberately limited to Cast FASTDEV:
  source diff checks, Cast-core/framework unit tests, debug/release Java
  compilation and lint on a standard public GitHub-hosted runner.
- Workflows use read-only repository permissions and do not sign or publish
  APKs, push commits, request upstream merges, or upload private diagnostics.
- A green FASTDEV result establishes **software-only qualification**, not
  successful YouTube/Deezer playback or an installable release.
- Full APK candidate/release work requires a separately reviewed workflow,
  source SHA provenance, signer verification and real-hardware acceptance.
  Never attach an official-looking release label to untested APKs.
- Never provide signing keys to `pull_request` jobs or make signing secrets
  available to untrusted fork contributions.

## Private boundary
Do not commit device identifiers, private networking data, nonpublic logcats,
reconstructed proprietary application bytecode, signed APKs, secrets,
credentials, personal test results or raw local research archives. Use small,
synthetic regression fixtures. Review every new source and test file for
license and disclosure before merging.

## Functional acceptance (independent of GitHub CI)
The final Cast implementation must preserve legacy/CXLESS connect behavior,
route discovery across chooser reopen, TV routes, audio singles, pairs and
groups, session generation and stale-callback protection, audio volume/mute,
disconnect semantics, and real playback on validated devices.

For internal developer hardware diagnostics, keep all evidence in a private
location and refer to source revisions without exposing private artifacts.

## Upstream workflows
This fork intentionally removes inherited automatic Crowdin sync, back-merging,
PR creation, dependency submission and private-key signing/release workflows.
If any of these are needed later, introduce them as separate reviewed PRs
with the least privileges necessary. Avoid blanket automated upstream merges.
