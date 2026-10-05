# -*- coding: utf-8 -*-
"""Unit tests for the catalog-driven resolver (no network required)."""
import os
import sys
import unittest

sys.path.insert(
    0,
    os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "python")),
)

import catalog_index
import resolver


def make_index():
    """A miniature catalog index that mirrors the real naming conventions."""
    return {
        'schemaVersion': catalog_index.INDEX_SCHEMA_VERSION,
        'version': 'test',
        'bundles': {
            'bundle_char': {'h': 'h1', 'u': 'internal/char.bundle', 'd': 'char_hash.bundle', 's': 10},
            'bundle_dating': {'h': 'h2', 'u': 'internal/dating.bundle', 'd': 'dating_hash.bundle', 's': 20},
            'bundle_censor': {'h': 'h3', 'u': 'internal/censor.bundle', 'd': 'censor_hash.bundle', 's': 5},
            'bundle_flat': {'h': 'h4', 'u': 'internal/flat.bundle', 'd': 'flat_hash.bundle', 's': 5},
            'bundle_cutscene': {'h': 'h5', 'u': 'internal/cutscene.bundle', 'd': 'cutscene_hash.bundle', 's': 5},
            'bundle_spine': {'h': 'h6', 'u': 'internal/spine.bundle', 'd': 'spine_hash.bundle', 's': 5},
            'bundle_hint': {'h': 'h7', 'u': 'internal/hint.bundle', 'd': 'hint_hash.bundle', 's': 5},
        },
        'exact': {
            'cutscene_char000101': {'bundle_cutscene': catalog_index.SCORE_SKELETON},
            'illust_char000104_69': {'bundle_flat': catalog_index.SCORE_FLAT_IMAGE},
        },
        'family': {
            'char000104': {'bundle_char': catalog_index.SCORE_PREFAB},
            'illust_dating16': {
                'bundle_dating': catalog_index.SCORE_PREFAB,
                'bundle_censor': catalog_index.SCORE_PREFAB,
            },
            'specialillust130': {
                'bundle_spine': catalog_index.SCORE_PLAYABLE,
                'bundle_flat': catalog_index.SCORE_PREFAB,
            },
        },
        'censored': {'illust_dating16': ['bundle_censor']},
    }


class StemHelpersTest(unittest.TestCase):

    def test_asset_stem(self):
        self.assertEqual(catalog_index.asset_stem('Char000104.SKEL.bytes'), 'char000104')
        self.assertEqual(catalog_index.asset_stem('cutscene_char000101.atlas.txt'), 'cutscene_char000101')
        self.assertEqual(catalog_index.asset_stem('illust_dating16.png'), 'illust_dating16')

    def test_variant_stem(self):
        self.assertEqual(catalog_index.variant_stem('char000104_12'), 'char000104')
        self.assertEqual(catalog_index.variant_stem('char000104'), 'char000104')

    def test_family_aliases(self):
        self.assertIn('char000104', catalog_index.family_aliases('illust_char000104_69'))
        self.assertIn('npc000004', catalog_index.family_aliases('illust_npc000004_1'))
        self.assertIn('cutscene_char000101', catalog_index.family_aliases('CutScene000101'))
        self.assertIn('specialillust130', catalog_index.family_aliases('SpecialIllust130'))


class ResolverTest(unittest.TestCase):

    def setUp(self):
        self.index = make_index()

    def test_char_family_bridge(self):
        result = resolver.resolve_mod_folder(
            ['char000104.png', 'char000104.atlas', 'char000104.skel'], self.index)
        self.assertEqual(result['resolutionState'], 'KNOWN')
        self.assertEqual(result['targetHash'], 'bundle_char')
        self.assertEqual(result['unresolvedFiles'], [])

    def test_json_is_treated_as_skel(self):
        result = resolver.resolve_mod_folder(
            ['char000104.png', 'char000104.atlas', 'char000104.json'], self.index)
        self.assertEqual(result['targetHash'], 'bundle_char')
        types = {t['originalFileName']: t['assetType'] for t in result['resolvedTargets']}
        self.assertEqual(types['char000104.json'], 'JsonSkeleton')

    def test_uncensored_bundle_wins(self):
        result = resolver.resolve_mod_folder(
            ['illust_dating16.png', 'illust_dating16.atlas', 'illust_dating16.skel'], self.index)
        self.assertEqual(result['targetHash'], 'bundle_dating')
        self.assertIn('bundle_censor', result['candidateHashes'])

    def test_playable_outweighs_prefab(self):
        result = resolver.resolve_mod_folder(
            ['specialillust130.png', 'specialillust130.atlas', 'specialillust130.skel'], self.index)
        self.assertEqual(result['targetHash'], 'bundle_spine')
        self.assertIn('bundle_flat', result['candidateHashes'])

    def test_variant_texture_uses_family(self):
        result = resolver.resolve_mod_folder(['char000104_3.png'], self.index)
        self.assertEqual(result['targetHash'], 'bundle_char')

    def test_flat_illustration_uses_exact_key(self):
        result = resolver.resolve_mod_folder(['illust_char000104_69.png'], self.index)
        self.assertEqual(result['targetHash'], 'bundle_flat')

    def test_cutscene_exact_skeleton(self):
        result = resolver.resolve_mod_folder(
            ['cutscene_char000101.png', 'cutscene_char000101_2.png',
             'cutscene_char000101.atlas.txt', 'cutscene_char000101.skel.bytes'], self.index)
        self.assertEqual(result['targetHash'], 'bundle_cutscene')

    def test_hint_is_last_resort(self):
        result = resolver.resolve_mod_folder(
            ['illust_special22.png', 'illust_special22.atlas', 'illust_special22.skel'],
            self.index, {'illust_special22': 'bundle_hint'})
        self.assertEqual(result['targetHash'], 'bundle_hint')
        self.assertEqual(result['resolvedTargets'][0]['matchStrategy'], 'FALLBACK')

    def test_unknown_mod(self):
        result = resolver.resolve_mod_folder(['totally_unknown.png'], self.index)
        self.assertEqual(result['resolutionState'], 'UNKNOWN')
        self.assertIsNone(result['targetHash'])
        self.assertEqual(result['unresolvedFiles'], ['totally_unknown.png'])

    def test_split_mod_is_invalid(self):
        result = resolver.resolve_mod_folder(
            ['char000104.png', 'char000104.atlas', 'specialillust130.skel'], self.index)
        self.assertEqual(result['resolutionState'], 'INVALID')
        self.assertIn('specialillust130.skel', result['unresolvedFiles'])


if __name__ == '__main__':
    unittest.main()
