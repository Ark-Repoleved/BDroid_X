# -*- coding: utf-8 -*-
"""Unit tests for CDN bundle path generation and index-based downloads."""
import os
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(
    0,
    os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "python")),
)

import cdn_downloader

BASE_URL = "https://cdn.bd2.pmang.cloud/ServerData/Android/HD/20260921132841"
DATING_BUNDLE = "f17229717fff2baf0b72e1eb98e19b91"
DATING_HASH = "d4f5b4ff340417f0e24c6382afde7afc"
DATING_KEY = "common-char-datingillust_assets_all_{}.bundle".format(DATING_HASH)
DATING_INTERNAL_ID = (
    "{BDNetwork.CdnInfo.Info}/Android/{BDNetwork.CdnInfo.Resolution}/"
    "{BDNetwork.CdnInfo.Version}/common-char-datingillust_assets_all.bundle"
)
ADDRESSABLE_RUNTIME_PATH = (
    "{UnityEngine.AddressableAssets.Addressables.RuntimePath}/"
    "common-localbgm_assets_all.bundle"
)


class DownloadUrlTests(unittest.TestCase):

    def test_current_internal_id_precedes_hashed_catalog_key(self):
        urls = cdn_downloader.generate_download_urls(
            BASE_URL, DATING_INTERNAL_ID, DATING_KEY, DATING_HASH)
        self.assertEqual(urls[0], BASE_URL + "/common-char-datingillust_assets_all.bundle")
        self.assertNotIn(DATING_HASH, urls[0])

    def test_addressables_runtime_path_uses_current_cdn_base(self):
        urls = cdn_downloader.generate_download_urls(
            BASE_URL, ADDRESSABLE_RUNTIME_PATH,
            "common-localbgm_assets_all_7b55852a64198d89d00a4fa1bd879753.bundle",
            "7b55852a64198d89d00a4fa1bd879753")
        self.assertEqual(urls[0], BASE_URL + "/common-localbgm_assets_all.bundle")

    def test_legacy_catalog_without_internal_id_keeps_filename_fallback(self):
        urls = cdn_downloader.generate_download_urls(BASE_URL, None, DATING_KEY, DATING_HASH)
        self.assertEqual(urls[0], BASE_URL + "/" + DATING_KEY)
        self.assertIn(BASE_URL + "/common-char-datingillust_assets_all.bundle", urls)

    def test_duplicate_urls_are_removed(self):
        urls = cdn_downloader.generate_download_urls(
            BASE_URL, DATING_INTERNAL_ID, DATING_INTERNAL_ID, "")
        self.assertEqual(len(urls), len(set(urls)))


class DownloadBundleTests(unittest.TestCase):

    def _index(self):
        return {
            'version': '20260921132841',
            'bundles': {
                DATING_BUNDLE: {
                    'hash': DATING_HASH,
                    'internal_id': DATING_INTERNAL_ID,
                    'download_name': DATING_KEY,
                    'size': 426991702,
                },
            },
        }

    def test_download_uses_internal_id_and_writes_expected_layout(self):
        captured = []

        def fake_download(url, output_path, bundle_size, progress_callback=None):
            captured.append(url)
            os.makedirs(os.path.dirname(output_path), exist_ok=True)
            with open(output_path, 'wb') as handle:
                handle.write(b'test bundle')
            return True, None

        with tempfile.TemporaryDirectory() as output_dir:
            with patch.object(cdn_downloader, '_try_download_bundle', fake_download):
                path, error = cdn_downloader.download_bundle(
                    self._index(), DATING_BUNDLE, 'HD', '20260921132841', output_dir)

            self.assertIsNone(error)
            self.assertTrue(os.path.isfile(path))
            self.assertEqual(captured[0], BASE_URL + "/common-char-datingillust_assets_all.bundle")
            self.assertEqual(
                os.path.relpath(path, output_dir).replace(os.sep, '/'),
                "{}/{}/__data".format(DATING_BUNDLE, DATING_HASH),
            )

    def test_unknown_bundle_reports_error(self):
        with tempfile.TemporaryDirectory() as output_dir:
            path, error = cdn_downloader.download_bundle(
                self._index(), 'deadbeef', 'HD', '20260921132841', output_dir)
        self.assertIsNone(path)
        self.assertIn('not present', error)


if __name__ == '__main__':
    unittest.main()
