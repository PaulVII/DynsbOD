#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
wikibox_pipeline.py

Two main operations:
  - statistics: stream a (possibly gzipped) JSON-lines file of Wikipedia infobox updates
      and produce a CSV with: infobox_name, count, attributes (JSON list)
  - generate: produce baseline / inserts / updates / deletes CSVs for one or more infobox types

Designed to be streaming / disk-backed so it can process very large inputs.

AFAIK, the wikibox dataset is currently only obtainable from archive.org:
https://web.archive.org/web/20130918225647if_/http://commondatastorage.googleapis.com/historicalinfobox/20120323-en-updates.json.gz
"""
from __future__ import annotations
import argparse
import gzip
import io
import json
import os
from collections import Counter, defaultdict
from typing import Dict, List, Set, Tuple

from tqdm import tqdm

# pandas used for final CSV writing
import pandas as pd


# ------------------------
# Utilities for streaming
# ------------------------
def open_maybe_gzip(path: str):
    """Open a plain or gzipped file for text reading."""
    if path.endswith(".gz"):
        return io.TextIOWrapper(gzip.open(path, "rb"), encoding="utf-8")
    else:
        return open(path, "r", encoding="utf-8")


def normalize_infobox_name(raw: str) -> str:
    """Normalize mapping similar to original script's map_infobox_to_filename."""
    if raw is None:
        return ""
    s = raw.lower()
    s = s.replace("infobox_", "infobox ")
    s = s.replace("infobox ", "")
    s = s.replace("template:", "")
    # collapse whitespace
    s = " ".join(s.split())
    return s


def normalize_attribute(key: str) -> str:
    if key is None:
        return ""
    return key.lower().replace(" ", "_")


# ------------------------
# Statistics operation
# ------------------------
def build_statistics(input_path: str, output_json: str, sample_limit: int = None, min_attr_count: int = 10) -> None:
    """
    Stream input_path (jsonlines) and collect:
      - count per infobox type
      - set of attributes observed per infobox type

    Writes CSV: infobox_name,count,attributes (JSON array)
    """
    counts: Counter = Counter()
    # count occurrences of each attribute key across the whole input
    attribute_counts_by_infobox: Dict[str, Counter] = {}

    with open_maybe_gzip(input_path) as fh:
        # We can't know total lines cheaply; use no total in tqdm by default.
        pbar = tqdm(fh, desc="scanning for statistics", unit="lines")
        for i, line in enumerate(pbar, start=1):
            if sample_limit and i > sample_limit:
                break
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except Exception:
                continue
            # defensive extraction
            infobox_name = None
            try:
                infobox_name = obj["attribute"][0]["infobox_name"]
            except Exception:
                # skip malformed lines:
                continue
            infobox_norm = normalize_infobox_name(infobox_name)
            counts[infobox_norm] += 1

            if not infobox_norm in attribute_counts_by_infobox:
                attribute_counts_by_infobox[infobox_norm] = Counter()

            # collect attributes observed in any update entry
            try:
                for attr in obj.get("attribute", []):
                    if "key" in attr:
                        k = normalize_attribute(attr["key"])
                        if k:
                            attribute_counts_by_infobox[infobox_norm][k] += 1
            except Exception:
                pass

    # prepare dataframe
    rows = []
    for infobox, cnt in counts.most_common():
        # sort attribute sby occurrence
        all_attrs = {k:v for k, v in attribute_counts_by_infobox[infobox].most_common()}
        rows.append({"infobox_name": infobox, "count": cnt, "attributes": all_attrs})

    json.dump(rows, open(output_json, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(f"Wrote statistics to {output_json}")


# ------------------------
# Dataset generation
# ------------------------

def stream_and_extract_infobox(input_path: str,
                               target_infoboxes: Set[str],
                               progress: bool = True,
                               allowed_attributes_by_infobox: Dict[str, Set[str]] = None) -> Dict[str, pd.DataFrame]:
    """
    Stream input and build a pandas DataFrame per infobox with all events.
    Each article's events are tracked with full state; first is insert, rest are updates,
    unless all attributes removed -> delete.
    Returns: dict mapping infobox_name -> DataFrame with columns: time, articleid, event_type, <attributes>
    """
    # Collect events per infobox: list of dict per infobox
    events_by_infobox: Dict[str, List[dict]] = defaultdict(list)
    article_ids: Dict[str, Dict[str, int]] = defaultdict(dict)  # infobox -> {title -> id}
    next_id_by_infobox: Dict[str, int] = defaultdict(lambda: 1)

    with open_maybe_gzip(input_path) as fh:
        it = fh
        if progress:
            it = tqdm(fh, desc="streaming and extracting infoboxes", unit="lines")
        for line in it:
            if not line.strip():
                continue
            try:
                obj = json.loads(line)
            except Exception:
                continue
            
            try:
                raw_infobox = obj["attribute"][0]["infobox_name"]
            except Exception:
                continue
            infobox_norm = normalize_infobox_name(raw_infobox)
            if infobox_norm not in target_infoboxes:
                continue

            article_title = obj.get("article_title", "")
            allowed_attrs = allowed_attributes_by_infobox.get(infobox_norm) if allowed_attributes_by_infobox else None

            # Assign article ID
            if article_title not in article_ids[infobox_norm]:
                article_ids[infobox_norm][article_title] = next_id_by_infobox[infobox_norm]
                next_id_by_infobox[infobox_norm] += 1
            article_id = article_ids[infobox_norm][article_title]

            # Group attribute entries by timestamp
            updates_by_timestamp = defaultdict(list)
            for attr_entry in obj.get("attribute", []):
                timestamp = attr_entry.get("timestamp", 0)
                updates_by_timestamp[timestamp].append(attr_entry)

            # Sort timestamps
            try:
                ordered_timestamps = sorted(updates_by_timestamp.keys(), key=lambda x: (float(x) if x else 0))
            except Exception:
                ordered_timestamps = sorted(updates_by_timestamp.keys())

            if not ordered_timestamps:
                continue

            # Track current known state for this article
            current_state: Dict[str, str] = {}
            prev_state: Dict[str, str] = {}
            exists = False

            for idx, ts in enumerate(ordered_timestamps):
                entries = updates_by_timestamp[ts]
                has_values_added = False
                # handle only the first occurrence of each attribute key in every individual update
                keys_already_seen = set()
                
                # Apply updates to current state
                for ent in entries:
                    key = normalize_attribute(ent.get("key", ""))
                    if not key:
                        continue
                    if key in keys_already_seen:
                        continue
                    keys_already_seen.add(key)
                    if allowed_attrs and key not in allowed_attrs:
                        continue
                    
                    new_val = ent.get("newvalue", "")
                    if new_val:
                        new_val = str(new_val).replace("\n", " ")
                        if new_val != current_state.get(key, None):
                            has_values_added = True
                        current_state[key] = new_val
                    else:
                        # Remove attribute if newvalue is empty
                        current_state.pop(key, None)

                if not has_values_added:
                    continue

                if infobox_norm == "disease" and article_title == "Complex regional pain syndrome":
                    print("disease update:")
                    print("ts:", ts)
                    print("entries:", entries)
                    print("current_state:", current_state)

                # print(entries)
                # print("state: ", current_state)
                # Determine event type: first is insert, others update, unless state is empty -> delete
                if not current_state:
                    if not exists:
                        continue
                    event_type = "delete"
                    exists = False
                elif not exists:
                    event_type = "insert"
                    if infobox_norm == "album":
                        print("insert event at ts:", ts)
                        print("current_state:", current_state)
                    exists = True
                else:
                    event_type = "update"
                # print(event_type)


                # Build event record with full current state
                event_record = {
                    "time": ts,
                    "articleid": article_id,
                    "event_type": event_type,
                    "article_title": article_title,
                }
                event_record.update(current_state if event_type != "delete" else prev_state)
                events_by_infobox[infobox_norm].append(event_record)
                prev_state = current_state.copy()

    # Convert to DataFrames sorted by time and articleid
    result = {}
    for infobox, events in events_by_infobox.items():
        if events:
            df = pd.DataFrame(events)
            df = df.sort_values(by=["time", "articleid"], ignore_index=True)
            result[infobox] = df
    
    return result



def sanitize_filename(name: str) -> str:
    """Make a filename-friendly representation of an infobox type."""
    safe = "".join([c if c.isalnum() or c in (" ", "_", "-") else "_" for c in name])
    safe = safe.replace(" ", "_")
    return safe[:200]


def process_infobox_dataframe(df: pd.DataFrame, out_dir: str, infobox_name: str) -> None:
    """
    Process a single infobox DataFrame to produce:
    - final_state.csv: constructed from last event per article (excluding deletes)
    - baseline.csv: first 50% of insert events (chronologically)
    - updates.csv: remaining events, with updates converted to delete+insert pairs
    All sorted by time, event type (inserts before deletes)
    """
    os.makedirs(out_dir, exist_ok=True)
    
    # Step 1: Build final_state from last event per article (excluding deletes)
    df = df.sort_values(by=["articleid", "time"], ignore_index=True)

    # Step 2: Convert all column values to dense rank (sort order preserved)
    # For each column, map distinct values to integers based on sorted order
    data_cols = [c for c in df.columns if c not in ["time", "articleid", "event_type"]]

    # replace by rank
    null_option = "nulls_first"  # could be parameterized
    for col in data_cols:
        # sort the column, and replace its value by its sorted position
        df[col] = df[col].rank(method='dense', na_option="top" if null_option == "nulls_first" else "bottom").astype('Int64')

    output_cols = sorted([c for c in df.columns if c not in ["event_type", "article_title", "articleid", "time"]])

    last_events = df.groupby("articleid").tail(1)
    final_state = last_events[last_events["event_type"] != "delete"].copy()
    final_state = final_state[output_cols]
    
    
    # Write final_state.csv
    final_state_path = os.path.join(out_dir, "final_state.csv")
    final_state.to_csv(final_state_path, index=False)
    print(f"Wrote {final_state_path}")

    # Step 3: Split first 50% of insert events as baseline
    insert_events = df[df["event_type"] == "insert"].sort_values(by=["time"])
    split_idx = len(insert_events) // 2 + 1
    if len(insert_events) == 1:
        split_idx = 1
    baseline_events = insert_events.iloc[:split_idx]
    remaining_inserts = insert_events.iloc[split_idx:]
    
    # Remaining events (excluding baseline inserts)
    other_events = df[df["event_type"] != "insert"]
    events_data = pd.concat([remaining_inserts, other_events], ignore_index=True)
    
    # Sort by articleid and time BEFORE processing to ensure prev_row tracking works
    events_data = events_data.sort_values(by=["articleid", "time"], ignore_index=True)
    
    # Step 4: Convert update events to delete+insert pairs
    # Data is now sorted by articleid and time, so previous row is the last state for same article
    expanded_rows = []
    article_states = {}  # Track last known state per article
    
    if not events_data.empty:
        for idx, row in events_data.iterrows():
            article_id = row["articleid"]
            
            if row["event_type"] == "update":
                # Get previous state from our tracking or baseline
                if article_id in article_states:
                    prev_state = article_states[article_id]
                else:
                    # Look up in baseline
                    baseline_for_article = baseline_events[baseline_events["articleid"] == article_id]
                    if not baseline_for_article.empty:
                        prev_state = baseline_for_article.iloc[-1]  # Get last baseline event
                    else:
                        # Article not in baseline - this shouldn't happen if updates follow inserts
                        # Skip the delete, just insert
                        prev_state = None
                
                # Create delete for previous state
                if prev_state is not None:
                    del_row = {"time": row["time"], "articleid": article_id, 
                            "event_type": "delete"} 
                    for col in data_cols:
                        if col in prev_state.index:
                            del_row[col] = prev_state[col]
                    expanded_rows.append(del_row)
                
                # Insert new state
                ins_row = {"time": row["time"], "articleid": article_id,
                        "event_type": "insert"} 
                for col in data_cols:
                    if col in row.index:
                        ins_row[col] = row[col]
                expanded_rows.append(ins_row)
                
                # Update tracking
                article_states[article_id] = row
                
            elif row["event_type"] == "insert":
                expanded_rows.append(row.to_dict())
                article_states[article_id] = row
            elif row["event_type"] == "delete":
                expanded_rows.append(row.to_dict())
                article_states.pop(article_id, None)  # Remove from tracking
        
        events_data_without_updates = pd.DataFrame(expanded_rows)
    else:
        events_data_without_updates = pd.DataFrame()
    
    # Step 5: Sort by time and event type (inserts before deletes)
    events_data_without_updates = events_data_without_updates.sort_values(by=["time", "event_type"], ascending=[True, True], ignore_index=True)
    events_data_with_updates = events_data.sort_values(by=["time", "event_type"], ascending=[True, True], ignore_index=True)

    baseline_events = baseline_events.sort_values(by=["time","event_type"], ascending=[True, True], ignore_index=True)

    # Step 6: Write to disk in CsvLoader-compatible format
    # First column: operation (insert/update/delete)
    # Second column: articleid (record ID)
    # Remaining columns: data columns
    
    def write_to_csv(df_out, path, include_operation =True):
        """
        Write file in CsvLoader-compatible format:
        - First column: operation (insert/update/delete)
        - Second column: articleid (record ID)
        - Remaining columns: data columns
        """
        # Write with header
        cols = ["event_type", "articleid"] + output_cols if include_operation == True else output_cols

        df_out[cols].to_csv(os.path.join(out_dir, path),index=False)
    
    write_to_csv(baseline_events, "baseline.csv", include_operation=False)
    write_to_csv(baseline_events, "baseline_marked.csv", include_operation=True)

    write_to_csv(events_data_without_updates, "events_without_updates.csv")
    write_to_csv(events_data_with_updates, "events_with_updates.csv")
    


def load_stats_file(stats_path: str) -> Tuple[List[str], Dict[str, Set[str]]]:
    """
    Load a stats JSON file and return:
      - list of infobox names (in order, by count descending)
      - dict mapping infobox_name -> set of allowed attribute names
    """
    with open(stats_path, "r", encoding="utf-8") as fh:
        data = json.load(fh)
    infobox_list = []
    allowed_attrs = {}
    for entry in data:
        name = entry.get("infobox_name", "")
        if not name:
            continue
        infobox_list.append(name)
        attrs = entry.get("attributes", {})
        # attributes can be dict {attr: count} or list [attr, ...]
        if isinstance(attrs, dict):
            allowed_attrs[name] = set(attrs.keys())
        elif isinstance(attrs, list):
            allowed_attrs[name] = set(attrs)
        else:
            allowed_attrs[name] = set()
    return infobox_list, allowed_attrs


def generate_for_infoboxes(input_path: str, infobox_list: List[str], out_root: str, keep_temp: bool = False,
                           allowed_attributes_by_infobox: Dict[str, Set[str]] = None):
    """
    High-level driver for dataset generation for given infobox names.
    Writes outputs under out_root/<infobox>/

    If allowed_attributes_by_infobox is provided, only those attributes will be included
    in baseline and events; all other attributes are discarded.
    """
    infoboxes_norm = set(normalize_infobox_name(x) for x in infobox_list)
    os.makedirs(out_root, exist_ok=True)

    print("Streaming and extracting infoboxes...")
    # Single pass: stream and build DataFrames per infobox
    dataframes = stream_and_extract_infobox(input_path, infoboxes_norm, 
                                           allowed_attributes_by_infobox=allowed_attributes_by_infobox)

    # Process each infobox DataFrame
    for infobox_norm, df in dataframes.items():
        safe_name = sanitize_filename(infobox_norm)
        out_dir = os.path.join(out_root, safe_name)
        print(f"\nProcessing infobox '{infobox_norm}' ({len(df)} events)...")
        process_infobox_dataframe(df, out_dir, infobox_norm)

    print("\nAll done. Output root:", out_root)


# ------------------------
# CLI
# ------------------------
def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="wikibox_pipeline.py", description="Process Wikipedia infobox update stream.")
    sub = p.add_subparsers(dest="command", required=True)

    # statistics
    sp = sub.add_parser("statistics", help="Scan file and produce infobox statistics JSON.")
    sp.add_argument("input", help="Path to JSON-lines input file (plain or .gz)")
    sp.add_argument("--output", "-o", default="infobox_statistics.json", help="Output JSON path")
    sp.add_argument("--sample", type=int, default=None, help="Optional: stop after N lines (useful for quick tests)")
    sp.add_argument("--min-attr-count", type=int, default=10, help="Minimum global attribute occurrences to keep (default 10)")

    # generate
    gp = sub.add_parser("generate", help="Generate baseline/events/final_state CSVs for specified infobox types.")
    gp.add_argument("input", help="Path to JSON-lines input file (plain or .gz)")
    gp.add_argument("--infobox", "-i", nargs="+", help="Target infobox name(s) (e.g. 'Infobox person'). Not required if --stats-file is used.")
    gp.add_argument("--stats-file", "-s", help="Path to stats JSON file (from 'statistics' command). Determines which infoboxes and attributes to include.")
    gp.add_argument("--out", "-o", default="output_datasets", help="Output root directory")
    gp.add_argument("--keep-temp", action="store_true", help="Keep intermediate temp files in each output folder (for debugging)")

    return p


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.command == "statistics":
        build_statistics(args.input, args.output, sample_limit=args.sample, min_attr_count=args.min_attr_count)
    elif args.command == "generate":
        infobox_list = args.infobox or []
        allowed_attrs = None
        if args.stats_file:
            # Load infoboxes and allowed attributes from stats file
            stats_infoboxes, allowed_attrs = load_stats_file(args.stats_file)
            if not infobox_list:
                # Use all infoboxes from stats file
                infobox_list = stats_infoboxes
            else:
                # Filter allowed_attrs to only requested infoboxes
                allowed_attrs = {k: v for k, v in allowed_attrs.items() if k in set(normalize_infobox_name(x) for x in infobox_list)}
        if not infobox_list:
            print("Error: Must provide --infobox or --stats-file")
            return
        generate_for_infoboxes(args.input, infobox_list, args.out, keep_temp=args.keep_temp,
                               allowed_attributes_by_infobox=allowed_attrs)
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
