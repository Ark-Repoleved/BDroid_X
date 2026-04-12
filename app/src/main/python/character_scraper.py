import requests
from bs4 import BeautifulSoup
import json
import sys
import os
import catalog_parser


def _fetch_character_metadata_map():
    url = "https://browndust2modding.pages.dev/characters"

    print("[Python] Fetching character list from website...")
    try:
        response = requests.get(url, timeout=15)
        response.raise_for_status()
    except requests.exceptions.RequestException as e:
        print(f"[Python] Error: Failed to retrieve the webpage. {e}", file=sys.stderr)
        return False, f"Failed to retrieve webpage: {e}", None

    print("[Python] Parsing HTML content...")
    soup = BeautifulSoup(response.text, 'html.parser')
    table_body = soup.find('tbody')
    if not table_body:
        print("[Python] Error: Could not find the data table (tbody) in the HTML.", file=sys.stderr)
        return False, "Could not find data table in HTML", None

    print("[Python] Scraping website for character metadata...")
    metadata_map = {}
    rows = table_body.find_all('tr')
    last_character = ""
    for row in rows:
        cells = row.find_all('td')
        character, file_id, costume = "", "", ""
        if len(cells) == 5:
            character = cells[0].get_text(strip=True)
            last_character = character
            file_id = cells[1].get_text(strip=True).lower()
            costume = cells[2].get_text(strip=True)
        elif len(cells) == 4:
            character = last_character
            file_id = cells[0].get_text(strip=True).lower()
            costume = cells[1].get_text(strip=True)

        if file_id and character and costume:
            metadata_map[file_id] = {"character": character, "costume": costume}

    print(f"[Python] Found metadata for {len(metadata_map)} file_ids from the website.")

    del soup
    import gc
    gc.collect()
    return True, "OK", metadata_map


def scrape_and_save_from_catalog(output_dir, version, catalog_content):
    """
    Builds characters.json using scraped website metadata plus a caller-provided
    game catalog payload. This function never downloads the catalog itself.
    """
    output_filename = "characters.json"
    output_path = os.path.join(output_dir, output_filename)

    if not version:
        return False, "Version not provided to scraper."
    if not catalog_content:
        return False, "Catalog content is missing."

    success, message, metadata_map = _fetch_character_metadata_map()
    if not success:
        return False, message

    print("[Python] Parsing provided catalog to build asset map...")
    try:
        asset_map = catalog_parser.parse_catalog_for_bundle_names(catalog_content)
        if not asset_map:
            raise Exception("Failed to parse catalog or catalog is empty.")
    except Exception as e:
        print(f"[Python] Error processing game catalog: {e}", file=sys.stderr)
        return False, f"Error processing game catalog: {e}"

    print("[Python] Asset map built successfully.")
    all_characters_data = []
    print(f"[Python] Generating character list based on {len(asset_map)} file_ids from the game catalog...")

    for file_id, bundles in asset_map.items():
        metadata = metadata_map.get(file_id, {
            "character": "Unknown Character",
            "costume": f"Unknown ({file_id})"
        })

        base_entry = {
            "character": metadata["character"],
            "file_id": file_id,
            "costume": metadata["costume"],
        }

        if "idle" in bundles and bundles["idle"]:
            idle_entry = base_entry.copy()
            idle_entry["type"] = "idle"
            idle_entry["hashed_name"] = bundles["idle"]
            all_characters_data.append(idle_entry)

        if "cutscene" in bundles and bundles["cutscene"]:
            cutscene_entry = base_entry.copy()
            cutscene_entry["type"] = "cutscene"
            cutscene_entry["hashed_name"] = bundles["cutscene"]
            all_characters_data.append(cutscene_entry)

        if "rhythm" in bundles and bundles["rhythm"]:
            rhythm_entry = base_entry.copy()
            rhythm_entry["type"] = "rhythm"
            rhythm_entry["hashed_name"] = bundles["rhythm"]
            all_characters_data.append(rhythm_entry)

    if not all_characters_data:
        print("[Python] Warning: No character data could be generated from the catalog.", file=sys.stderr)

    print(f"[Python] Saving {len(all_characters_data)} total entries to {output_path}...")
    final_data = {
        "version": version,
        "characters": all_characters_data
    }

    try:
        with open(output_path, 'w', encoding='utf-8') as f:
            json.dump(final_data, f, indent=4, ensure_ascii=False)
    except IOError as e:
        print(f"[Python] Error: Failed to write to file {output_path}. {e}", file=sys.stderr)
        return False, f"Failed to write to file: {e}"

    print(f"[Python] Success! Data saved to {output_path}")
    return True, "Scraper completed successfully."


def scrape_and_save(output_dir, version):
    """
    Backward-compatible wrapper. Downloads are handled externally in the new
    metadata refresh flow, so this path should be avoided by new callers.
    """
    return False, "scrape_and_save without provided catalog_content is deprecated."
