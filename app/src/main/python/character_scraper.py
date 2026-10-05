# -*- coding: utf-8 -*-
"""Builds characters.json (display metadata) and bundle_hints.json.

Display names/costumes come from the community metadata page; bundle targets
come from the catalog index (and, for families the catalog cannot bridge, from
the page's own bundle column as a last-resort hint).
"""
import json
import re
import sys
from pathlib import Path

import requests
from bs4 import BeautifulSoup

METADATA_URL = 'https://browndust2modding.pages.dev/characters'

_HASH_RE = re.compile(r'^[0-9a-f]{32}$', re.IGNORECASE)
_CHAR_ID_RE = re.compile(r'^char(\d{6})$', re.IGNORECASE)
_FAMILY_RE = re.compile(
    r'^(char\d{6}|npc\w+|illust_dating\w+|illust_talk\w+|illust_special\w+|specialillust\w+)$',
    re.IGNORECASE,
)


def _fetch_character_metadata_map():
    """Return (metadata_map, hint_map) scraped from the community page."""
    print('[Python] Fetching character list from website...')
    try:
        response = requests.get(METADATA_URL, timeout=20)
        response.raise_for_status()
    except requests.exceptions.RequestException as e:
        return False, 'Failed to retrieve webpage: {}'.format(e), None, None

    soup = BeautifulSoup(response.text, 'html.parser')
    table_body = soup.find('tbody')
    if not table_body:
        return False, 'Could not find data table in HTML', None, None

    metadata_map = {}
    hint_map = {}
    last_character = ''

    for row in table_body.find_all('tr'):
        cells = [c.get_text(strip=True) for c in row.find_all('td')]
        hashes = [c.lower() for c in cells if _HASH_RE.match(c)]
        if len(cells) == 5:
            character = cells[0]
            last_character = character
            file_id = cells[1].lower()
            costume = cells[2]
        elif len(cells) == 4:
            character = last_character
            file_id = cells[0].lower()
            costume = cells[1]
        else:
            continue
        if not file_id or not costume:
            continue
        metadata_map[file_id] = {'character': character or 'Unknown Character', 'costume': costume}
        if hashes:
            hint_map[file_id] = hashes[-1]

    print('[Python] Metadata for {} file ids ({} bundle hints).'.format(
        len(metadata_map), len(hint_map)))
    return True, 'OK', metadata_map, hint_map


def _best_family_bundle(index, stem, censored):
    """Pick the strongest non-censored bundle for a family stem."""
    scores = (index.get('family') or {}).get((stem or '').lower())
    if not scores:
        return None
    blocked = set(censored.get((stem or '').lower(), ()))
    candidates = [(bundle, score) for bundle, score in scores.items() if bundle not in blocked]
    if not candidates:
        return None
    candidates.sort(key=lambda item: (-item[1], item[0]))
    return candidates[0][0]


def build_character_data(index, metadata_map, hint_map):
    """Assemble characters.json entries + resolver hints from the index."""
    index = index or {}
    bundles = index.get('bundles') or {}
    censored = index.get('censored') or {}

    file_ids = set(metadata_map) | set(index.get('family') or {})
    entries = []
    hints = {}

    for file_id in sorted(file_ids):
        lowered = file_id.lower()
        if not _FAMILY_RE.match(lowered):
            continue
        metadata = metadata_map.get(lowered) or {}
        if not metadata:
            continue

        base = {
            'character': metadata.get('character', 'Unknown Character'),
            'file_id': lowered,
            'costume': metadata.get('costume', 'Unknown ({})'.format(lowered)),
        }

        idle_bundle = _best_family_bundle(index, lowered, censored)
        if idle_bundle:
            idle_entry = dict(base)
            idle_entry['type'] = 'idle'
            idle_entry['hashed_name'] = idle_bundle
            entries.append(idle_entry)

        char_match = _CHAR_ID_RE.match(lowered)
        if char_match:
            cutscene_bundle = _best_family_bundle(
                index, 'cutscene_char{}'.format(char_match.group(1)), censored)
            if cutscene_bundle:
                cutscene_entry = dict(base)
                cutscene_entry['type'] = 'cutscene'
                cutscene_entry['hashed_name'] = cutscene_bundle
                entries.append(cutscene_entry)

        hint = hint_map.get(lowered)
        if hint and hint in bundles:
            hints[lowered] = hint
            if not idle_bundle:
                hint_entry = dict(base)
                hint_entry['type'] = 'idle'
                hint_entry['hashed_name'] = hint
                entries.append(hint_entry)

    return entries, hints


def scrape_and_save_from_index(output_dir, version, index):
    """Write characters.json and bundle_hints.json. Returns (success, message)."""
    if not version:
        return False, 'Version not provided to scraper.'
    if not index:
        return False, 'Catalog index is missing.'

    success, message, metadata_map, hint_map = _fetch_character_metadata_map()
    if not success:
        return False, message

    entries, hints = build_character_data(index, metadata_map, hint_map)
    if not entries:
        return False, 'No character data could be generated from the catalog.'

    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)
    with open(output_path / 'characters.json', 'w', encoding='utf-8') as handle:
        json.dump({'version': version, 'characters': entries}, handle,
                  indent=4, ensure_ascii=False)

    with open(output_path / 'bundle_hints.json', 'w', encoding='utf-8') as handle:
        json.dump(hints, handle, ensure_ascii=False, separators=(',', ':'))

    print('[Python] Saved {} character entries and {} hints.'.format(len(entries), len(hints)))
    return True, 'Scraper completed successfully.'


def load_bundle_hints(output_dir):
    try:
        with open(Path(output_dir) / 'bundle_hints.json', 'r', encoding='utf-8') as handle:
            data = json.load(handle)
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def scrape_and_save(output_dir, version):
    """Deprecated wrapper kept for backward compatibility."""
    return False, 'scrape_and_save without an index is deprecated.'
