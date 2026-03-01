"""
Shared utilities for thesis chart notebooks.

Usage in any notebook:
    from chart_utils import *

This sets up:
  - pandas, seaborn, matplotlib with thesis-ready serif styling
  - Common path constants (dynsbod_root, benchmark_data_path, datasets_path, thesis_charts_path)
  - Parsing helpers for benchmark results, intermediate CSVs, static algorithm output
  - Plotting helpers (save_figure, human_format, format_dataset_name, ...)
"""

import csv
import json
import re
from pathlib import Path

import matplotlib.pyplot as plt
import pandas as pd
import seaborn as sns

# these imports are unuused here, but frequently used in charts that import this, so keep it
import numpy as np
from collections import defaultdict
from matplotlib.ticker import FuncFormatter

# ─── Style setup ──────────────────────────────────────────────────────────────

sns.set_theme(style="ticks", font="serif")
plt.rcParams["font.family"] = "serif"
plt.rcParams["font.serif"] = [
    "Times New Roman",
    "DejaVu Serif",
    "Bitstream Vera Serif",
    "Computer Modern Roman",
]

# ─── Path constants ───────────────────────────────────────────────────────────

dynsbod_root = Path("/Users/paulsieben/Programming/Masterarbeit/algo/")
benchmark_data_path = dynsbod_root / "benchmark"
datasets_path = dynsbod_root / "datasets"
thesis_charts_path = Path("/Users/paulsieben/Programming/Masterarbeit/text/charts")


# ─── Formatting helpers ──────────────────────────────────────────────────────

def human_format(num, pos=None):
    """Format numbers with k, m, b suffixes."""
    magnitude = 0
    while abs(num) >= 1000:
        magnitude += 1
        num /= 1000.0
    return f'{num:.0f}{["", "k", "m", "B", "T"][magnitude]}'


def format_dataset_name(name: str, index=None):
    """
    Format dataset name for display in plots.

    Rules:
    - Use only the part before the first underscore
    - Except keep: _baseline, _final_state, and ncvoter_15
    - Optionally add subfigure letter if index is provided

    Args:
        name: Dataset name to format
        index: Optional index for subfigure letter (0 -> (a), 1 -> (b), etc.)

    Returns:
        Formatted dataset name
    """
    if "_final_state" in name:
        formatted = name.replace("_final_state", "_final")
    elif "_baseline" in name or "ncvoter-reduced" in name:
        formatted = name
    else:
        formatted = name.split("_")[0]

    if index is not None:
        letter = chr(ord("a") + index)
        formatted = f"({letter}) {formatted}"

    return formatted


def save_figure(filename: str, width=None, height=None, scaling=None):
    """Save the current figure as PDF to both local dir and thesis charts dir."""
    fig = plt.gcf()
    if width is not None or height is not None or scaling is not None:
        current_size = fig.get_size_inches()
        new_width = (width / 2.54) if width is not None else current_size[0]
        new_height = (height / 2.54) if height is not None else current_size[1]
        new_height *= scaling if scaling else 1.0
        new_width *= scaling if scaling else 1.0
        fig.set_size_inches(new_width, new_height)

    plt.savefig(filename + ".pdf", format="pdf", bbox_inches="tight")
    plt.savefig(thesis_charts_path / (filename + ".pdf"), format="pdf", bbox_inches="tight")


# ─── Benchmark metrics parsing ────────────────────────────────────────────────

def read_benchmark_metrics(file: str):
    """Read a benchmark_metrics.json file and return a tidy DataFrame."""
    with open(file, "r") as f:
        benchmark_data = json.load(f)

    records = []
    for combo_key, runs in benchmark_data.items():
        parts = combo_key.split("_")
        algo_version = parts[0].replace("algo", "")
        java_version = parts[1].replace("java", "")
        dataset = "_".join(parts[2:])

        for run_idx, run_data in enumerate(runs):
            record = {
                "algo_version": algo_version,
                "java_version": java_version,
                "dataset": dataset,
                "run_number": run_idx,
                "stdout_file": run_data["stdout_file"],
                "max_mem_mb": run_data["max_mem_mb"],
                "total_ms": run_data["total_ms"],
            }
            deletions = run_data.get("deletions", [])
            record["deletion_time_ms"] = (
                sum(d["time_ms"] for d in deletions) if deletions else None
            )
            increments = run_data.get("increments", [])
            record["insertion_time_ms"] = (
                sum(d["time_ms"] for d in increments) if increments else None
            )
            records.append(record)

    return pd.DataFrame(records)


# ─── Static algorithm results ─────────────────────────────────────────────────

def parse_static_results(identifier):
    """Parse static algorithm results for a given identifier.
    Returns (results_list, error_type) where error_type is 'timeout', 'oom', or None.
    """
    results_dir = dynsbod_root / "benchmark/static_algorithm/results"
    results = []
    error_type = None

    metrics_file = results_dir / "benchmark_metrics.json"
    if metrics_file.exists():
        try:
            with open(metrics_file, "r") as f:
                metrics_data = json.load(f)
                if identifier in metrics_data:
                    for run in metrics_data[identifier]:
                        if "Error" in run and "Timeout" in run.get("Error", ""):
                            error_type = "timeout"
                            break
        except Exception as e:
            print(f"Error reading metrics JSON: {e}")

    for file in results_dir.glob(f"{identifier}_run*_stdout.txt"):
        try:
            content = file.read_text()
            mem_cost = None
            total_time = None

            if "java.lang.OutOfMemoryError" in content:
                error_type = "oom"

            for line in content.splitlines():
                if "MemoryCost: " in line:
                    mem_match = re.search(r"MemoryCost:\s*(\d+(?:\.\d+)?)", line)
                    if mem_match:
                        mem_cost = float(mem_match.group(1))
                if "TotalTime:" in line:
                    time_match = re.search(r"TotalTime:\s*(\d+(?:\.\d+)?)", line)
                    if time_match:
                        total_time = float(time_match.group(1)) / 1000.0

            if mem_cost is not None or total_time is not None:
                results.append({"memory": mem_cost, "time": total_time})
        except Exception as e:
            print(f"Error parsing {file}: {e}")

    print(f"static search for {identifier=} yielded {results=} {error_type=}")
    return results, error_type


def parse_deletion_stdout_stats(file_path):
    """Parse OdValidatorStats from a stdout file."""
    with open(file_path, "r") as f:
        content = f.read()

    match = re.search(r"Statistics: OdValidatorStats\(([\d,]+)\)", content)
    if not match:
        return None

    values = [int(x) for x in match.group(1).split(",")]

    return {
        "validationCount": values[0],
        "contextIteratorsCreated": values[1],
        "contextIteratorsReused": values[2],
        "contextIteratorSteps": values[3],
        "contextIteratorStepsNew": values[4],
        "contextIteratorStepsWithoutMerging": values[5],
        "bitsetsProcessed": values[6],
        "bitsetsSkippedByLimit": values[7],
        "directRevalidations": values[8],
        "normalRevalidations": values[9],
        "violationsFound": values[10],
        "revalidatedByInsertion": values[11],
        "violatedByInsertion": values[12],
    }


# ─── Intermediate CSV helpers ─────────────────────────────────────────────────

def preprocess_intermediate_df(df, filter_last_increment=True):
    """
    Preprocess intermediate CSV data with common transformations.

    Adds timeDelta, operationsDiff, cacheEvitionsDiff, timePerOperation, totalODs.
    Optionally filters to the last incrementIdx.
    """
    df["timeDelta"] = df["timeElapsedMs"].diff()
    df["operationsDiff"] = df["lastOperationId"].diff()
    df["cacheEvitionsDiff"] = df["numCacheEvictions"].diff()

    df.loc[0, "timeDelta"] = df.loc[0, "timeElapsedMs"]
    df.loc[0, "operationsDiff"] = df.loc[0, "lastOperationId"]
    df.loc[0, "cacheEvitionsDiff"] = df.loc[0, "numCacheEvictions"]

    df["timePerOperation"] = df["timeDelta"] / df["operationsDiff"]
    df["totalODs"] = df["currentComptibleOds"] + df["currentConstantOds"]

    if filter_last_increment and "incrementIdx" in df.columns:
        df = df[df["incrementIdx"] == df["incrementIdx"].max()]

    return df


def load_intermediate_runs(files, filter_last_increment=True):
    """
    Load and combine all runs for a dataset.

    Parameters:
        files: List of Path objects or single Path for intermediate CSV files
        filter_last_increment: If True, only keep rows from last incrementIdx

    Returns:
        Combined DataFrame with 'run' column
    """
    if not isinstance(files, list):
        files = [files]

    all_runs = []
    for file in files:
        try:
            df = pd.read_csv(file)
        except Exception as e:
            raise Exception(f"Error reading {file}: {e}")

        df = preprocess_intermediate_df(df, filter_last_increment)

        run_match = re.search(r"run(\d+)", Path(file).stem)
        df["run"] = int(run_match.group(1)) if run_match else 0

        all_runs.append(df)

    return pd.concat(all_runs, ignore_index=True) if all_runs else pd.DataFrame()


def normalize_intermediate_name(file_path):
    """Strip run number and prefix from an intermediate CSV filename."""
    name = re.sub(r"_run\d+_stdout\.intermediate\.csv$", "", Path(file_path).name)
    name = re.sub(r"^algo\d+_java\d+_", "", name)
    return name


def split_intermediate_variant(name):
    """Split an intermediate name into (base_name, variant)."""
    if name.endswith("_second_half"):
        return name[: -len("_second_half")], "second_half"
    if name.endswith("_first_half"):
        return name[: -len("_first_half")], "first_half"
    return name, "full"


def get_first_run(df):
    """Return rows belonging to the first (smallest) run number."""
    if df.empty:
        return df
    return df[df["run"] == df["run"].min()]


# ─── CSV dimension counting ──────────────────────────────────────────────────

def count_csv_dimensions(file_path, delimiter=","):
    """Count rows and columns in a CSV file."""
    with open(file_path, "r") as f:
        reader = csv.reader(f, delimiter=delimiter)
        rows = list(reader)
        if not rows:
            return 0, 0
        return len(rows), len(rows[0]) if rows else 0


def get_dataset_name(file_path):
    """Get dataset name from file path.
    If stem is final_state, use parent folder + '_final_state'.
    """
    name = Path(file_path).stem.split("_")[0]
    if Path(file_path).stem == "final_state":
        name = f"{Path(file_path).parent.stem}_final_state"
    return name


# ─── Progress bar parsing ─────────────────────────────────────────────────────

def parse_progress_from_stdout(file_path):
    """
    Extract progress data from stdout file.
    Returns: (current_ops, total_ops, elapsed_time_seconds)
    """
    with open(file_path, "r") as f:
        lines = f.readlines()

    for line in reversed(lines):
        if "Progress:" in line and "Speed:" in line:
            ops_match = re.search(r"\((\d+)/(\d+)\)", line)
            if not ops_match:
                continue

            current = int(ops_match.group(1))
            total = int(ops_match.group(2))

            speed_match = re.search(r"Speed: ([\d.]+) (items/s|s/item)", line)
            if not speed_match:
                continue

            speed_value = float(speed_match.group(1))
            speed_unit = speed_match.group(2)

            if speed_unit == "items/s":
                elapsed_time = current / speed_value if speed_value > 0 else None
            else:
                elapsed_time = current * speed_value

            return current, total, elapsed_time

    return None, None, None


def get_time_at_operation(intermediate_csv, target_operation):
    """Get the time elapsed when reaching a specific operation number."""
    try:
        df = pd.read_csv(intermediate_csv)
        if len(df) == 0:
            return None

        df_filtered = df[df["lastOperationId"] <= target_operation]
        if len(df_filtered) == 0:
            return None

        closest_row = df_filtered.iloc[-1]
        return closest_row["timeElapsedMs"] / 1000.0
    except Exception as e:
        print(f"Error reading {intermediate_csv}: {e}")
        return None


def parse_progress_bar(content):
    """Parse the last progress bar from stdout to determine completion percentage and if OOM occurred."""
    progress_matches = list(
        re.finditer(
            r"Progress: \|[=-]+\s*\| ([\d.]+)% \((\d+)/(\d+)\)", content
        )
    )

    oom_occurred = "OutOfMemoryError" in content

    if not progress_matches:
        return None, oom_occurred

    last_match = progress_matches[-1]
    percent = float(last_match.group(1))
    current = int(last_match.group(2))
    total = int(last_match.group(3))

    return {"percent": percent, "current": current, "total": total}, oom_occurred


# ─── LaTeX table formatting helpers ──────────────────────────────────────────

def format_numeric(val):
    """Format a numeric value or return as-is if string/None."""
    if isinstance(val, (int, float)) and pd.notna(val):
        return f"{val:.2f}" if isinstance(val, float) else f"{int(val)}"
    return val


def should_bold(dynsbod_val, static_val, is_better_fn):
    """Determine if a DynSBOD value should be bolded in a LaTeX table."""
    if not isinstance(dynsbod_val, (int, float)):
        return False
    if isinstance(static_val, str) and static_val in ["OOM", "TIMEOUT"]:
        return True
    if isinstance(static_val, (int, float)):
        return is_better_fn(dynsbod_val, static_val)
    return False


def format_with_bold(val, should_be_bold):
    """Format value with optional bold LaTeX \\winner command."""
    if not isinstance(val, (int, float)) or not should_be_bold:
        return f"{val:.2f}" if isinstance(val, (int, float)) else val
    return f"\\winner{{{val:.2f}}}"
