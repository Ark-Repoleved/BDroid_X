
import requests
from bs4 import BeautifulSoup
import json
import sys
import os

def scrape_and_save(output_dir):
    """
    Scrapes character data from the browndust2modding.pages.dev website
    and saves it as characters.json in the specified output directory.
    Handles rowspans and creates both IDLE and CUTSCENE entries.
    """
    URL = "https://browndust2modding.pages.dev/characters"
    output_filename = "characters.json"
    output_path = os.path.join(output_dir, output_filename)
    
    print(f"[Python] Fetching data from {URL}...")

    try:
        response = requests.get(URL, timeout=15)
        response.raise_for_status()
    except requests.exceptions.RequestException as e:
        print(f"[Python] Error: Failed to retrieve the webpage. {e}", file=sys.stderr)
        # Return False to indicate failure
        return False

    print("[Python] Parsing HTML content...")
    soup = BeautifulSoup(response.text, 'html.parser')

    table_body = soup.find('tbody')
    if not table_body:
        print("[Python] Error: Could not find the data table (tbody) in the HTML.", file=sys.stderr)
        return False

    all_characters_data = []
    rows = table_body.find_all('tr')
    
    print(f"[Python] Found {len(rows)} potential entries. Processing...")

    last_character = ""

    for row in rows:
        cells = row.find_all('td')
        
        if len(cells) == 5:
            character = cells[0].get_text(strip=True)
            last_character = character
            file_id = cells[1].get_text(strip=True)
            costume = cells[2].get_text(strip=True)
            idle_hash_val = cells[3].get_text(strip=True)
            cutscene_hash_val = cells[4].get_text(strip=True)
        elif len(cells) == 4:
            character = last_character
            file_id = cells[0].get_text(strip=True)
            costume = cells[1].get_text(strip=True)
            idle_hash_val = cells[2].get_text(strip=True)
            cutscene_hash_val = cells[3].get_text(strip=True)
        else:
            continue

        if not all([character, costume, file_id]):
            continue

        base_entry = {
            "character": character,
            "file_id": file_id,
            "costume": costume,
        }

        idle_entry = base_entry.copy()
        idle_entry["type"] = "idle"
        idle_entry["hashed_name"] = idle_hash_val if idle_hash_val and idle_hash_val != '-' else ""
        all_characters_data.append(idle_entry)

        cutscene_entry = base_entry.copy()
        cutscene_entry["type"] = "cutscene"
        cutscene_entry["hashed_name"] = cutscene_hash_val if cutscene_hash_val and cutscene_hash_val != '-' else ""
        all_characters_data.append(cutscene_entry)

    if not all_characters_data:
        print("[Python] Warning: No data was successfully extracted.", file=sys.stderr)
    
    print(f"[Python] Saving {len(all_characters_data)} entries to {output_path}...")

    try:
        with open(output_path, 'w', encoding='utf-8') as f:
            json.dump(all_characters_data, f, indent=4, ensure_ascii=False)
    except IOError as e:
        print(f"[Python] Error: Failed to write to file {output_path}. {e}", file=sys.stderr)
        return False

    print(f"[Python] Success! Data saved to {output_path}")
    return True
