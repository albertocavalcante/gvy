// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { expect } from "chai";
import { VersionChecker } from "../../../../src/features/update/VersionChecker";

describe("VersionChecker", () => {
  const versionChecker = new VersionChecker();

  describe("compareVersions", () => {
    for (const [older, newer] of [
      ["1.0.0-100", "1.0.0-2a"],
      ["1.0.0-alpha10", "1.0.0-alpha2"],
      ["1.0.0-Z", "1.0.0-a"],
      ["1.0.0-alpha.2", "1.0.0-alpha.10"],
      ["1.0.0-alpha.1", "1.0.0-alpha.1.0"],
      ["9007199254740992.0.0", "9007199254740993.0.0"],
      ["1.9007199254740992.0", "1.9007199254740993.0"],
      ["1.0.9007199254740992", "1.0.9007199254740993"],
      ["1.0.0-9007199254740992", "1.0.0-9007199254740993"],
    ]) {
      it(`orders ${older} before ${newer} by SemVer precedence`, () => {
        expect(versionChecker.compareVersions(older, newer)).to.be.lessThan(0);
        expect(versionChecker.compareVersions(newer, older)).to.be.greaterThan(
          0,
        );
      });
    }

    it("follows the SemVer specification's prerelease precedence example", () => {
      const versions = [
        "1.0.0-alpha",
        "1.0.0-alpha.1",
        "1.0.0-alpha.beta",
        "1.0.0-beta",
        "1.0.0-beta.2",
        "1.0.0-beta.11",
        "1.0.0-rc.1",
        "1.0.0",
      ];
      for (let index = 1; index < versions.length; index++) {
        expect(
          versionChecker.compareVersions(versions[index - 1], versions[index]),
        ).to.be.lessThan(0);
      }
    });

    for (const version of ["1.2.3", "v1.2.3-alpha.1"]) {
      it(`ignores build metadata for ${version}`, () => {
        expect(
          versionChecker.compareVersions(
            `${version}+001`,
            `${version}+build.2`,
          ),
        ).to.equal(0);
        expect(
          versionChecker.compareVersions(`${version}+001`, version),
        ).to.equal(0);
      });
    }

    it("keeps SNAPSHOT and dev provenance precedence without downgrading", () => {
      expect(
        versionChecker.compareVersions("v2.0.0-SNAPSHOT", "1.9.9"),
      ).to.be.greaterThan(0);
      expect(
        versionChecker.compareVersions("0.5.4-dev+gabc.dirty", "0.5.3"),
      ).to.be.greaterThan(0);
      expect(
        versionChecker.compareVersions("0.5.4-dev+gabc", "v0.5.4"),
      ).to.be.lessThan(0);
    });

    it("orders a pre-release before its release", () => {
      expect(
        versionChecker.compareVersions("0.5.0-rc.1", "0.5.0"),
      ).to.be.lessThan(0);
      expect(
        versionChecker.compareVersions("0.5.0", "v0.5.0-SNAPSHOT"),
      ).to.be.greaterThan(0);
      expect(
        versionChecker.compareVersions("0.5.0-rc.1", "0.5.0-rc.1"),
      ).to.equal(0);
    });

    it("returns 0 for identical versions (with or without v prefix)", () => {
      expect(versionChecker.compareVersions("1.0.0", "1.0.0")).to.equal(0);
      expect(versionChecker.compareVersions("v2.5.3", "v2.5.3")).to.equal(0);
      expect(versionChecker.compareVersions("v1.0.0", "1.0.0")).to.equal(0);
      expect(versionChecker.compareVersions("1.0.0", "v1.0.0")).to.equal(0);
    });

    it("orders versions by major/minor/patch", () => {
      expect(
        versionChecker.compareVersions("2.0.0", "1.9.9"),
      ).to.be.greaterThan(0);
      expect(
        versionChecker.compareVersions("1.2.0", "1.1.9"),
      ).to.be.greaterThan(0);
      expect(
        versionChecker.compareVersions("1.0.2", "1.0.1"),
      ).to.be.greaterThan(0);

      expect(versionChecker.compareVersions("1.0.0", "2.0.0")).to.be.lessThan(
        0,
      );
      expect(versionChecker.compareVersions("1.1.0", "1.2.0")).to.be.lessThan(
        0,
      );
      expect(versionChecker.compareVersions("1.0.1", "1.0.2")).to.be.lessThan(
        0,
      );
    });

    it("returns 0 if either version is invalid", () => {
      expect(versionChecker.compareVersions("local", "1.0.0")).to.equal(0);
      expect(versionChecker.compareVersions("1.0.0", "unknown")).to.equal(0);
      expect(
        versionChecker.compareVersions("invalid", "also-invalid"),
      ).to.equal(0);
    });
  });

  describe("isValidVersion", () => {
    for (const version of [
      "01.2.3",
      "1.02.3",
      "1.2.03",
      "1.2.3-",
      "1.2.3+",
      "1.2.3-alpha..1",
      "1.2.3-alpha.01",
      "1.2.3-alpha.",
      "1.2.3-.alpha",
      "1.2.3-00",
      "1.2.3-alpha_1",
      "1.2.3-α",
      "1.2.3junk",
      "1.2.3.4",
      "1.2.3+build.",
      "1.2.3+build..1",
      "1.2.3+build+extra",
      "1.2.3+build_1",
      "1.2.3\n",
      "v1.2.3 ",
    ]) {
      it(`rejects the complete malformed version ${JSON.stringify(version)}`, () => {
        expect(versionChecker.isValidVersion(version)).to.equal(false);
        expect(versionChecker.compareVersions(version, "1.0.0")).to.equal(0);
        expect(versionChecker.compareVersions("1.0.0", version)).to.equal(0);
      });
    }

    for (const version of [
      "1.2.3-0",
      "1.2.3-alpha.0",
      "1.2.3-01a",
      "1.2.3-alpha-01",
      "1.2.3--",
      "1.2.3+001",
      "v0.5.4-SNAPSHOT",
      "0.5.4-dev+gabc.dirty",
      "1.2.3-alpha.1+001.sha",
      "9007199254740993.0.0",
    ]) {
      it(`accepts the complete version ${version}`, () => {
        expect(versionChecker.isValidVersion(version)).to.equal(true);
      });
    }

    it("accepts semver strings (with optional v prefix)", () => {
      expect(versionChecker.isValidVersion("1.0.0")).to.equal(true);
      expect(versionChecker.isValidVersion("v2.5.3")).to.equal(true);
      expect(versionChecker.isValidVersion("0.0.1")).to.equal(true);
    });

    it("rejects local/unknown/empty", () => {
      expect(versionChecker.isValidVersion("local")).to.equal(false);
      expect(versionChecker.isValidVersion("unknown")).to.equal(false);
      expect(versionChecker.isValidVersion("")).to.equal(false);
      expect(versionChecker.isValidVersion("   ")).to.equal(false);
    });

    it("rejects non-semver strings", () => {
      expect(versionChecker.isValidVersion("1.0")).to.equal(false);
      expect(versionChecker.isValidVersion("v1")).to.equal(false);
      expect(versionChecker.isValidVersion("not-a-version")).to.equal(false);
    });
  });

  describe("buildReleaseInfo", () => {
    it("returns null for missing tag_name", () => {
      expect(
        versionChecker.buildReleaseInfo({
          tag_name: "",
          html_url: "https://example.invalid",
          published_at: "2020-01-01T00:00:00Z",
          assets: [],
        }),
      ).to.equal(null);
    });

    it("returns null for missing html_url", () => {
      expect(
        versionChecker.buildReleaseInfo({
          tag_name: "v1.2.3",
          html_url: "",
          published_at: "2020-01-01T00:00:00Z",
          assets: [
            {
              name: "any.jar",
              browser_download_url: "https://example.invalid/any.jar",
            },
          ],
        }),
      ).to.equal(null);
    });

    it("returns null for missing published_at", () => {
      expect(
        versionChecker.buildReleaseInfo({
          tag_name: "v1.2.3",
          html_url: "https://github.com/x/y/releases/tag/v1.2.3",
          published_at: "",
          assets: [
            {
              name: "any.jar",
              browser_download_url: "https://example.invalid/any.jar",
            },
          ],
        }),
      ).to.equal(null);
    });

    it("returns null when no jar asset exists", () => {
      expect(
        versionChecker.buildReleaseInfo({
          tag_name: "v1.2.3",
          html_url: "https://github.com/x/y/releases/tag/v1.2.3",
          published_at: "2020-01-01T00:00:00Z",
          assets: [
            {
              name: "checksums.txt",
              browser_download_url: "https://example.invalid/checksums.txt",
            },
          ],
        }),
      ).to.equal(null);
    });

    it("selects a linux-amd64 jar when available", () => {
      const info = versionChecker.buildReleaseInfo({
        tag_name: "v1.2.3",
        html_url: "https://github.com/x/y/releases/tag/v1.2.3",
        published_at: "2020-01-01T00:00:00Z",
        assets: [
          {
            name: "checksums.txt",
            browser_download_url: "https://example.invalid/checksums.txt",
          },
          {
            name: "groovy-lsp-1.2.3-windows.jar",
            browser_download_url: "https://example.invalid/win.jar",
          },
          {
            name: "groovy-lsp-1.2.3-linux-amd64.jar",
            browser_download_url: "https://example.invalid/linux.jar",
          },
        ],
      });

      expect(info).to.not.equal(null);
      expect(info?.tagName).to.equal("v1.2.3");
      expect(info?.version).to.equal("1.2.3");
      expect(info?.releaseUrl).to.equal(
        "https://github.com/x/y/releases/tag/v1.2.3",
      );
      expect(info?.publishedAt).to.equal("2020-01-01T00:00:00Z");
      expect(info?.downloadUrl).to.equal("https://example.invalid/linux.jar");
    });

    it("falls back to the first jar if no linux-amd64 jar exists", () => {
      const info = versionChecker.buildReleaseInfo({
        tag_name: "v1.2.3",
        html_url: "https://github.com/x/y/releases/tag/v1.2.3",
        published_at: "2020-01-01T00:00:00Z",
        assets: [
          {
            name: "groovy-lsp-1.2.3.jar",
            browser_download_url: "https://example.invalid/main.jar",
          },
          {
            name: "checksums.txt",
            browser_download_url: "https://example.invalid/checksums.txt",
          },
        ],
      });

      expect(info).to.not.equal(null);
      expect(info?.tagName).to.equal("v1.2.3");
      expect(info?.version).to.equal("1.2.3");
      expect(info?.releaseUrl).to.equal(
        "https://github.com/x/y/releases/tag/v1.2.3",
      );
      expect(info?.publishedAt).to.equal("2020-01-01T00:00:00Z");
      expect(info?.downloadUrl).to.equal("https://example.invalid/main.jar");
    });
  });
});
