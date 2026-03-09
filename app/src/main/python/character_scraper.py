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

    # --- New Refactored Logic ---
    # First, scrape the website to get human-readable metadata.
    # This data is treated as secondary and is used to "enrich" the primary data from the catalog.
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
    
    # Clean up BeautifulSoup object to free memory
    del soup
    import gc
    gc.collect()

    # Now, iterate through the asset_map (from the official catalog) as the source of truth.
    all_characters_data = []
    print(f"[Python] Generating character list based on {len(asset_map)} file_ids from the game catalog...")

    for file_id, bundles in asset_map.items():
        # Get metadata from the scraped data, with a fallback for missing entries.
        metadata = metadata_map.get(file_id, {
            "character": "Unknown Character",
            "costume": f"Unknown ({file_id})"
        })

        base_entry = {
            "character": metadata["character"],
            "file_id": file_id,
            "costume": metadata["costume"],
        }

        # Create idle entry if it exists in the catalog.
        if "idle" in bundles and bundles["idle"]:
            idle_entry = base_entry.copy()
            idle_entry["type"] = "idle"
            idle_entry["hashed_name"] = bundles["idle"]
            all_characters_data.append(idle_entry)

        # Create cutscene entry if it exists in the catalog.
        if "cutscene" in bundles and bundles["cutscene"]:
            cutscene_entry = base_entry.copy()
            cutscene_entry["type"] = "cutscene"
            cutscene_entry["hashed_name"] = bundles["cutscene"]
            all_characters_data.append(cutscene_entry)

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
