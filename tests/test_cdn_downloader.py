import base64
import json
import os
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(
    0,
    os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "python")),
)

import cdn_downloader


BUNDLE_PROVIDER = "UnityEngine.ResourceManagement.ResourceProviders.AssetBundleProvider"
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


def _ascii_catalog_string(value):
    encoded = value.encode("ascii")
    return b"\x00" + struct.pack("<i", len(encoded)) + encoded


def _json_catalog_object(value):
    assembly_name = b""
    type_name = b""
    encoded_json = json.dumps(value).encode("utf-16")
    return (
        b"\x07"
        + bytes([len(assembly_name)])
        + assembly_name
        + bytes([len(type_name)])
        + type_name
        + struct.pack("<i", len(encoded_json))
        + encoded_json
    )


def _single_bundle_catalog():
    key_data = _ascii_catalog_string(DATING_KEY)
    bucket_data = struct.pack("<4i", 1, 0, 1, 0)
    extra_data = _json_catalog_object(
        {
            "m_BundleName": DATING_BUNDLE,
            "m_Hash": DATING_HASH,
            "m_BundleSize": 426991702,
        }
    )
    # Entry fields: internal ID index, provider index, dependency key/hash,
    # extra-data offset, primary key index, and resource type.
    entry_data = struct.pack("<9i", 1, 0, 1, 0, 0, 0, 0, 0, 0)
    return {
        "m_ProviderIds": ["UnusedProvider", BUNDLE_PROVIDER],
        "m_InternalIds": [DATING_INTERNAL_ID],
        "m_BucketDataString": base64.b64encode(bucket_data).decode("ascii"),
        "m_KeyDataString": base64.b64encode(key_data).decode("ascii"),
        "m_ExtraDataString": base64.b64encode(extra_data).decode("ascii"),
        "m_EntryDataString": base64.b64encode(entry_data).decode("ascii"),
    }


class CdnDownloadUrlTests(unittest.TestCase):
    def test_current_internal_id_precedes_hashed_catalog_key(self):
        urls = cdn_downloader._generate_download_urls(
            BASE_URL,
            DATING_KEY,
            DATING_BUNDLE,
            DATING_HASH,
            DATING_INTERNAL_ID,
        )

        self.assertEqual(
            urls[0],
            BASE_URL + "/common-char-datingillust_assets_all.bundle",
        )
        self.assertNotIn(DATING_HASH, urls[0])

    def test_addressables_runtime_path_uses_current_cdn_base(self):
        urls = cdn_downloader._generate_download_urls(
            BASE_URL,
            "common-localbgm_assets_all_7b55852a64198d89d00a4fa1bd879753.bundle",
            "9b063e04bff1c205d7a9430434f1bef7",
            "7b55852a64198d89d00a4fa1bd879753",
            ADDRESSABLE_RUNTIME_PATH,
        )

        self.assertEqual(
            urls[0],
            BASE_URL + "/common-localbgm_assets_all.bundle",
        )

    def test_legacy_catalog_without_internal_id_keeps_filename_fallback(self):
        urls = cdn_downloader._generate_download_urls(
            BASE_URL, DATING_KEY, DATING_BUNDLE, DATING_HASH
        )

        self.assertEqual(urls[0], BASE_URL + "/" + DATING_KEY)
        self.assertIn(
            BASE_URL + "/common-char-datingillust_assets_all.bundle",
            urls,
        )

    def test_bundle_lookup_uses_internal_id_index_from_current_catalog(self):
        catalog = _single_bundle_catalog()
        captured_urls = []

        def fake_download(url, output_path, bundle_size, progress_callback=None):
            captured_urls.append(url)
            os.makedirs(os.path.dirname(output_path), exist_ok=True)
            with open(output_path, "wb") as output_file:
                output_file.write(b"test bundle")
            return True, None

        with tempfile.TemporaryDirectory() as output_dir:
            with patch.object(cdn_downloader, "_try_download_bundle", fake_download):
                output_path, error = cdn_downloader.find_and_download_bundle(
                    catalog,
                    "20260921132841",
                    "HD",
                    DATING_BUNDLE,
                    output_dir,
                )

            self.assertIsNone(error)
            self.assertTrue(os.path.isfile(output_path))
            self.assertEqual(
                captured_urls[0],
                BASE_URL + "/common-char-datingillust_assets_all.bundle",
            )


if __name__ == "__main__":
    unittest.main()
