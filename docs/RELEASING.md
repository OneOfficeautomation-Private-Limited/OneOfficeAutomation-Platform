# Releasing to customers

Customer releases are published to the private repository
[VirupakshaPrabhu/OOA_Customer_Build](https://github.com/VirupakshaPrabhu/OOA_Customer_Build). Development stays
here in `oneofficeautomation`.

| Repository | Holds |
| --- | --- |
| `oneofficeautomation` | All source code, tests, the vendor tools (License Studio) and these documents |
| `OOA_Customer_Build` | Customer-facing `README.md`, `INSTALL.md` and `RELEASES.md`; each release's `ooa-customer-<version>.zip` under Releases |

## Publish a release

1. Raise `version` in `platform\build.gradle.kts`, for example from `0.1.0` to `0.2.0`. A version can be published once.
2. Add the release's changes at the top of `RELEASES.md` in `OOA_Customer_Build`, then commit and push that repository.
3. From `platform`, run:

   ```
   powershell -ExecutionPolicy Bypass -File scripts\publish-customer-release.ps1 -Notes "What changed in one line"
   ```

   The script:
   - builds the customer zip and refuses to publish if the vendor tools, a key or a license file is in it;
   - writes the software bill of materials (`gradlew :cli:sbom`: every third-party library with its version and
     SHA-256, CycloneDX 1.5) and a manifest of the release files signed with the vendor key in
     `%USERPROFILE%\OOA License Keys\private.key` (`-SigningKey` for another place), and checks it;
   - signs in with the GitHub account git already uses;
   - creates release `v<version>` with the zips, `ooa-<version>.manifest.json` and `ooa-<version>-sbom.cdx.json`
     attached, noting the source commit it was built from.

   Add `-Draft` to check a release on GitHub before customers can see it.

## Give customers the release

The repository is private, so a download link only works for people signed in to GitHub with access. Either:

- send the zip yourself (download it from the release), together with the customer's `.lic` file from License
  Studio; or
- invite the customer's GitHub user as a read-only collaborator of `OOA_Customer_Build` (Settings > Collaborators).

License files are never published: each customer gets their own `.lic` directly from you.

## How customers check a download

`ooa release verify ooa-<version>.manifest.json` (with any installed release, in the folder holding the downloads, or
`--dir`) checks the manifest's signature against the vendor key built into the product, then each file's size and
SHA-256: PASS, FAIL (changed: do not install) or MISSING. A manifest that was edited is refused as a whole. Customers
without an earlier install compare the SHA-256 values with `certutil -hashfile FILE SHA256`. The SBOM is also inside
each package as `sbom.cdx.json`, for their vulnerability scanners.
