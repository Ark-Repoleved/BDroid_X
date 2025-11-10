
import requests
from bs4 import BeautifulSoup
import json
import sys
import os
import threading
import catalog_parser

def scrape_and_save(output_dir, version):
    """
    Scrapes character data from the browndust2modding.pages.dev website,
    then uses the file_id to find the correct bundle_name from the game's catalog,
    and saves it as characters.json in the specified output directory.
    """
    URL = "https://browndust2modding.pages.dev/characters"
    output_filename = "characters.json"
    output_path = os.path.join(output_dir, output_filename)
    
    print("[Python] Fetching character list from website...")
    try:
        response = requests.get(URL, timeout=15)
        response.raise_for_status()
    except requests.exceptions.RequestException as e:
        print(f"[Python] Error: Failed to retrieve the webpage. {e}", file=sys.stderr)
        return False, f"Failed to retrieve webpage: {e}"

    print("[Python] Parsing HTML content...")
    soup = BeautifulSoup(response.text, 'html.parser')
    table_body = soup.find('tbody')
    if not table_body:
        print("[Python] Error: Could not find the data table (tbody) in the HTML.", file=sys.stderr)
        return False, "Could not find data table in HTML"

    # --- New logic to get bundle names from catalog ---
    asset_map = {}
    try:
        if not version:
            raise Exception("Version not provided to scraper.")

        print(f"[Python] Downloading catalog version {version}...")
        # These are dummy args for the downloader, which expects a cache and lock
        dummy_cache = {}
        dummy_lock = threading.Lock()
        catalog_content, error = catalog_parser.download_catalog(output_dir, "HD", version, dummy_cache, dummy_lock, lambda msg: print(f"[Python] {msg}"))
        if error:
            raise Exception(f"Failed to download catalog: {error}")

        print("[Python] Parsing catalog to build asset map...")
        asset_map = catalog_parser.parse_catalog_for_bundle_names(catalog_content)
        if not asset_map:
            raise Exception("Failed to parse catalog or catalog is empty.")
        print("[Python] Asset map built successfully.")

    except Exception as e:
        print(f"[Python] Error processing game catalog: {e}", file=sys.stderr)
        print("[Python] Cannot proceed without catalog data. Aborting.", file=sys.stderr)
        return False, f"Error processing game catalog: {e}"
    # --- End of new logic ---

    all_characters_data = []
    rows = table_body.find_all('tr')
    
    print(f"[Python] Found {len(rows)} potential entries. Processing and matching with catalog data...")

    last_character = ""
    for row in rows:
        cells = row.find_all('td')
        
        if len(cells) == 5:
            character = cells[0].get_text(strip=True)
            last_character = character
            file_id = cells[1].get_text(strip=True).lower()
            costume = cells[2].get_text(strip=True)
        elif len(cells) == 4:
            character = last_character
            file_id = cells[0].get_text(strip=True).lower()
            costume = cells[1].get_text(strip=True)
        else:
            continue

        if not all([character, costume, file_id]):
            continue

        base_entry = {
            "character": character,
            "file_id": file_id,
            "costume": costume,
        }

        # Look up bundle names from our new asset_map
        bundle_names = asset_map.get(file_id, {})
        idle_bundle_name = bundle_names.get("idle", "")
        cutscene_bundle_name = bundle_names.get("cutscene", "")

        idle_entry = base_entry.copy()
        idle_entry["type"] = "idle"
        idle_entry["hashed_name"] = idle_bundle_name
        all_characters_data.append(idle_entry)

        cutscene_entry = base_entry.copy()
        cutscene_entry["type"] = "cutscene"
        cutscene_entry["hashed_name"] = cutscene_bundle_name
        all_characters_data.append(cutscene_entry)

    if not all_characters_data:
        print("[Python] Warning: No data was successfully extracted or matched.", file=sys.stderr)
    
    print(f"[Python] Saving {len(all_characters_data)} entries to {output_path}...")

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
