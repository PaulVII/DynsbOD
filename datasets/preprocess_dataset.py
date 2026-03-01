import argparse
from pathlib import Path
import pandas as pd
from itertools import product
import numpy as np
import json

null_values = ['', '?']
null_permutations = [''.join(p) for p in product(*zip('null', 'NULL'))]
null_values.extend(null_permutations)

date_formats = [
    '%Y-%m-%dT%H:%M:%SZ', '%a, %d %b %Y %H:%M:%S %Z',
    '%m/%d/%Y', '%d.%m.%Y', '%Y-%m-%d', '%Y-%m-%d%z'
]

def load_data(file, sep, null_option, num_cols, skip_constant_cols, no_header):
    # if file has no header, set column names to A, B, C, ...
    if no_header:
        df = pd.read_csv(file, sep=sep,low_memory=False, header=None, names=[chr(i) for i in range(65, 91)], keep_default_na=False, na_values=null_values)
        # remove emptyy cols 
        df = df.dropna(axis=1, how='all')
    else:
        df = pd.read_csv(file, sep=sep, low_memory=False, keep_default_na=False, na_values=null_values)

    if skip_constant_cols:
        df = df.loc[:, df.nunique() > 1]
        print(f"After removing constant columns, {len(df.columns)} columns remain.")

    if num_cols is not None:
        df = df.sample(n=num_cols, axis=1, random_state=42)
        # rename so that columns are A, B, C, ...
        if no_header:
            df.columns = [chr(i) for i in range(65, 65 + len(df.columns))]
    
    # Try to infer dates
    for col in df.columns:
        if df[col].dtype == object:
            for date_format in date_formats:
                try:
                    df[col] = pd.to_datetime(df[col], format=date_format, errors='raise')
                    break
                except Exception:
                    pass
    # NULL handling
    if null_option == 'remove':
        df = df.dropna()
        print(f"After removing NULLs, {len(df)} rows remain.")
    return df

def convert_to_int(df, null_option):
    for col in df.columns:
        # sort the column, and replace its value by its sorted position
        df[col] = df[col].rank(method='dense', na_option="top" if null_option == "nulls_first" else "bottom").astype('Int64')
    return df

def new_filename(start: int, stop: int, original_file: Path):
    return f"samples/{original_file.stem}_{start}_{stop}.csv"

def create_config_file(args: argparse.Namespace, starts_ends):
    """Generate a single config listing initial + incremental cumulative samples and expected DistOD result files."""
    original_file = Path(args.file)
    stem = original_file.stem

    # Sample CSV paths (relative from repository root usage perspective)
    # Stored by the script inside datasets/samples/
    sample_csvs = [f"./datasets/samples/{stem}_{start}_{end}.csv" for (start, end) in starts_ends]
    # HyOD result files as produced by run_distod: samples/results_hyod_{file.name}.txt
    # Mirror inside datasets for consistency with previous pattern
    result_files = [f"./datasets/samples/results_hyod_{stem}_0_{end}.csv.txt" for (_, end) in starts_ends]

    config = {
        "$schema": "schema/config.schema.json",
        "data": {
            "initial": sample_csvs[0]
        },
        "results": {
            "initial": result_files[0]
        },
        "csv": {
            "delimiter": args.sep,
            "hasHeader": True
        }
    }

    if len(sample_csvs) > 1:
        config["data"]["increments"] = sample_csvs[1:]
        config["results"]["increments"] = result_files[1:]

    # Ensure configs directory exists (sibling of datasets)
    configs_dir = Path("../configs")
    configs_dir.mkdir(exist_ok=True)

    config_path = configs_dir / f"{stem}_config.json"
    with open(config_path, "w") as f:
        json.dump(config, f, indent=2)

    print(f"Wrote config: {config_path}")

def main():
    parser = argparse.ArgumentParser(description="Sample batches from a CSV/TSV file.")
    parser.add_argument("file", help="Path to the CSV/TSV file")
    parser.add_argument("sizes", nargs='*', type=int, help="Batch sizes (number of rows per sample)")
    parser.add_argument("--no-header", action='store_true', help="Indicate that the input file has no header row")
    parser.add_argument("--rest", action='store_true', help="Create one additional sample with all remaining rows")
    parser.add_argument("--sep", default=',', help="Column separator (default: ','). Use '\\t' for TSV.")
    parser.add_argument("--null_option", choices=['remove', 'nulls_first', 'nulls_last'], default='remove', help="NULL handling option")
    parser.add_argument("--num-cols", type=int, default=None, help="Number of columns to sample. If none, use all columns.")
    parser.add_argument("--convert-int", action='store_true', help="Convert all columns to integers if possible")
    parser.add_argument("--skip-constant-cols", action='store_true', help="Skip constant columns")
    parser.add_argument("--complete", action='store_true', help="Output the entire dataset to complete/ folder without splitting")
    args = parser.parse_args()

    df = load_data(args.file, args.sep, args.null_option, args.num_cols, args.skip_constant_cols, args.no_header)
    if args.convert_int:
        print(df.info())
        df = convert_to_int(df, args.null_option)

    original_file = Path(args.file)

    # Handle --complete option: save entire dataset and exit
    if args.complete:
        complete_dir = Path("complete")
        complete_dir.mkdir(exist_ok=True)
        output_path = complete_dir / original_file.name
        df.to_csv(output_path, index=False, sep=args.sep)
        print(f"Complete dataset saved to: {output_path}")
        return
    else: assert len(args.sizes) > 0

    available_df = df.copy()
    cumulative_df = pd.DataFrame(columns=df.columns)

    # Create samples directory if it doesn't exist
    Path("samples").mkdir(exist_ok=True)

    starts_ends = []

    def generate_sample(size, available_df, cumulative_df, starts_ends,sep):
        if len(available_df) < size:
            print(f"Warning: Not enough remaining rows. Requested {size}, but only {len(available_df)} available.")
            size = len(available_df)
        sample = available_df.sample(n=size, random_state=42 + size)
        before = len(cumulative_df)
        after = before + size
        starts_ends.append((before, after))
        cumulative_df = pd.concat([cumulative_df, sample], ignore_index=True)
        sample.to_csv(new_filename(before, after, original_file), index=False, sep=sep)
        print((before, after))
        print(len(available_df))
        if after > len(sample):
            cumulative_df.to_csv(new_filename(0, after, original_file), index=False, sep=sep)

        return available_df.drop(sample.index), cumulative_df


    for i, size in enumerate(args.sizes, 1):
        if len(available_df) < size:
            print(f"Warning: Not enough remaining rows for batch {i}. Requested {size}, but only {len(available_df)} available.")
            size = len(available_df)

        available_df, cumulative_df = generate_sample(size, available_df, cumulative_df, starts_ends, args.sep)
        
    if args.rest and not available_df.empty:
        available_df, cumulative_df = generate_sample(len(available_df), available_df, cumulative_df, starts_ends, args.sep)

    create_config_file(args, starts_ends)

if __name__ == "__main__":
    main()
