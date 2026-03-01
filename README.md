# DynsbOD

DynsbOD is an algorithm for **incrementally discovering set-based order dependencies (ODs)** with both tuple deletion and insertion support. It was developed as part of my master thesis.

Given a dataset that changes over time (inserts, updates, deletes), DynsbOD maintains the set of valid ODs without re-running discovery from scratch after each change.

## Prerequisites

- **Java 11+**
- **Scala 3 / sbt** (tested with Scala 3.7.1) for building DynsbOD. Can be omitted by using the precompiled JAR (see below)
- **Python 3** (for dataset preprocessing and benchmark experiments)

### Installing Scala and sbt

The recommended way to install Scala and sbt is via [Coursier](https://get-coursier.io/):

```bash
# macOS
brew install coursier/formulas/coursier && cs setup

# Linux
curl -fL https://github.com/coursier/coursier/releases/latest/download/cs-x86_64-pc-linux.gz | gzip -d > cs && chmod +x cs && ./cs setup
```

`cs setup` installs a JVM, Scala, and sbt. See the [Coursier documentation](https://get-coursier.io/docs/cli-install) for details. Alternatively, install sbt directly from [scala-sbt.org](https://www.scala-sbt.org/download.html).

## Building

You can either run the project directly with sbt (see next section) or build an executable JAR file with all dependencies with the following command

```bash
sbt assembly
```

This produces `target/scala-3.7.1/dynsbod-assembly-1.0.0.jar`.

## Running DynsbOD

### With the assembled JAR

A pre-compiled JAR is included at `target/scala-3.7.1/dynsbod-assembly-1.0.0.jar`.

```bash
java -jar target/scala-3.7.1/dynsbod-assembly-1.0.0.jar path/to/config.json
```

### With sbt

```bash
sbt "runMain main path/to/config.json"
```

## Configuration

DynsbOD is configured via a JSON config file. Example configs for both insert-only and dynamic (update/delete) datasets are provided in `configs/examples/`. See also `src/main/resources/schema/config.schema.json` for the full JSON schema.

### Data paths

| Property | Type | Required | Description |
|---|---|---|---|
| `data.initial` | string | No | Path to the initial/baseline CSV file |
| `data.increments` | string[] | No | Ordered list of increment CSV file paths, processed sequentially |

### Results

| Property | Type | Required | Description |
|---|---|---|---|
| `results.initial` | string | Conditional | Path to expected OD results for the initial data. **Required if `data.initial` is set** — DynsbOD needs to know which ODs hold on the baseline to proceed incrementally |
| `results.increments` | string[] | No | Paths to expected OD results after each increment. If provided, DynsbOD outputs a diff between the discovered ODs and the expected results, which is useful for correctness validation |

Result files are plain-text OD lists produced by a static algorithm such as HyOD.

### CSV options

| Property | Type | Default | Description |
|---|---|---|---|
| `csv.delimiter` | string | `","` | Column separator |
| `csv.hasHeader` | boolean | `false` | Whether the CSV has a header row |
| `csv.canHaveDeletions` | boolean | `false` | Whether the CSV files contain the two meta columns for update/delete operations (see [CSV Format](#csv-format)). **If set to `true`, all referenced CSV files — including the baseline — must have the two meta columns.** If set to `false`, the first two columns are treated as regular data columns |
| `csv.columnsToInclude` | string[] | all | Optional whitelist of column names to load. Only these columns are used for OD discovery |

### Algorithm options

| Property | Type | Default | Description |
|---|---|---|---|
| `algo.deletionMode` | `"none"` \| `"afterinsert"` \| `"deleteonly"` | `"none"` | Allows to test deletes on static datasets. When csv.canHaveDeletions is true, this must be set to `none`. `none`: Tret the dataset as-is. `afterinsert`: after inserting all tuples, generate another increment that deletes all tuples `deleteonly`: start with a full dataset and delete all contained tuples (requires results for the final increment in results.increments ) |
| `algo.minMaxCacheThreshold` | integer | `20` | MinMaxCache stores the minimum and maximum values of clusters with more than this amount of tuples. To deactivate MinMaxCache, set to a number higher than the dataset size |
| `algo.skipRevalidationByInsert` | boolean | `true` | Skip revalidation of ODs after insert operations. Recommended to keep true, as this is usually slower |
| `algo.disableEfficientUpdates` | boolean | `false` | Disable efficient update processing that tracks which attributes changed during an update |
| `algo.optimizeBuildOrder` | boolean | `true` | Find shared subpartitions when validating after a tuple deletion to reduce work. Highly recommended to activate |
| `algo.showProgressBar` | boolean | `true` | Display a progress bar during processing |
| `algo.useLazyList` | boolean | `true` | Use lazy evaluation for constructing partitions during tuple deletion handling. When `false`, uses eagerly evaluated IndexedSeq. Recommended to keep true for performance gains if validation returns early |

### Output options

| Property | Type | Required | Description |
|---|---|---|---|
| `output` | string | No | Path to write the discovered ODs |
| `intermediateStatsPath` | string | No | Path prefix for intermediate statistics CSVs. Stats are collected periodically and written to `{prefix}_insert_{idx}.csv` / `{prefix}_delete_{idx}.csv` |
| `perTupleTimingPath` | string | No | Path for per-tuple timing CSV. Records the processing time for each individual insert, delete, or update operation |

## CSV Format

### Insert-only (default)

Standard CSV with an optional header row. Every row is treated as an insert:

```csv
col_a,col_b,col_c
127,54,210
251,69,490
```

### With updates and deletes

When `csv.canHaveDeletions` is `true`, the CSV must contain **two additional meta columns** at the beginning:

| Column | Description |
|---|---|
| `event_type` | Operation type: `insert`, `update`, or `delete` |
| Record ID | A stable integer identifier for the record. This is used to optimize execution if only a subset of attributes changed during an update |

```csv
event_type,record_id,col_a,col_b,col_c
insert,1001,127,54,210
update,1001,130,54,215
delete,1001,130,54,215
```

These two meta columns are stripped before processing; only the remaining data columns are used for OD discovery.

**Important:** If `canHaveDeletions` is `true`, **all** CSV files referenced in the config (including the baseline) must have the two meta columns. If `canHaveDeletions` is `false`, the first two columns are treated as regular data columns.

## Dataset Preprocessing

### preprocess_dataset.py

Use `datasets/preprocess_dataset.py` to prepare your datasets. This is required as DynsbOD only supports integer attributes. It handles NULL values, optional rank-encoding to integers, splitting into multiple increments to output intermediate results, and auto-generating config files.

```bash
cd datasets
python preprocess_dataset.py <input.csv> <batch_sizes...> [options]
```

Example:

```bash
python preprocess_dataset.py flights.csv 100 1000 10000 --convert-int --null_option remove --rest
```

This produces sample CSVs in `datasets/samples/` and a matching config JSON in `configs/`.

Run `python preprocess_dataset.py --help` for the full list of options (NULL handling, column sampling, rank-encoding, etc.).

### wikibox_parser.py

`datasets/wikipedia/wikibox_parser.py` generates datasets from the [Wikipedia Historical Infobox Dataset](https://web.archive.org/web/20130918225647if_/http://commondatastorage.googleapis.com/historicalinfobox/20120323-en-updates.json.gz) (see [here](https://research.google/pubs/whad-wikipedia-historical-attributes-data/) for details about this dataset), producing CSV files with realistic insert/update/delete patterns.

First, generate statistics about which infobox types and attributes are available:

```bash
cd datasets/wikipedia
python wikibox_parser.py statistics <input.json.gz> -o infobox_statistics.json
```

Copy the data for infoboxes and attributes you want to generate a dataset for into a new json file (like the example datasets/wikipedia/thesis.json that was used to create tables for the evaluation chapter of the thesis) 
Then, generate baseline and event CSV files for specific infobox types:

```bash
python wikibox_parser.py generate <input.json.gz> -s infobox_statistics.json -i "Infobox person" -o output_datasets
```

This produces per infobox type:
- `baseline.csv` / `baseline_marked.csv` — first 50% of insert events (the marked variant includes the two meta columns)
- `events_without_updates.csv` — remaining events with updates expanded to delete+insert pairs
- `events_with_updates.csv` — remaining events with native update events preserved
- `final_state.csv` — state of the dataset after all updates are applied

All values are converted to dense integer ranks. Run `python wikibox_parser.py --help` for all options.

## Internal Benchmarking (Benchmark.scala)

`Benchmark.scala` exists to quickly estimate performance during development. It scans all config files placed in `configs/benchmark/`, runs DynsbOD on each configuration, validates correctness against expected results, and produces a `benchmark_report.json` summarizing timings and memory usage.

Run it with:

```bash
sbt "runMain Benchmark"
```

## Thesis Experiments

The `benchmark/` Python package is separate from `Benchmark.scala` and is used to generate the experimental data presented in the thesis. It contains the following runners:

- **`dynsbod_runner.py`** — Runs DynsbOD (requires building the JAR with dependencies) with configurable algorithm settings, JVM options, and dataset presets. Used for all DynsbOD experiments in the thesis evaluation.
- **`dynfd_runner.py`** — Runs DynFD (`benchmark/dynfd.jar`), a competing incremental FD discovery algorithm. Used as a baseline for comparison in the thesis evaluation.
- **`static_algorithm/run_static_algorithm.py`** — Runs the static HyOD algorithm.(`datasets/HyOD_modified.jar`) on complete datasets. Used as a static baseline in the thesis evaluation. Our changes to the HyOD algorithm can be found in [this repository](https://github.com/PaulVII/HyOD/)
- **`incpod_runner.py`** — Runs Hydra+/IncPOD, another competing approach. This runner was not used in the final thesis evaluation. IncPOD.jar and Hydra+.jar can be optained [here](https://github.com/Lab-yi/Alg)

The `benchmark/experiments/` directory contains individual experiment scripts (column scalability, memory usage, deletion features, intermediate performance, etc.). Run all experiments:

```bash
python -m benchmark.experiments
```

Or run a single experiment:

```bash
python -m benchmark.experiments.<experiment_name>
```

For example: `python -m benchmark.experiments.columns` or `python -m benchmark.experiments.memory`.

The Jupyter notebooks in `thesis_charts/` consume the experiment output data and produce the figures and tables used in the thesis.

We also include the results from our runs in .zip archives in the benchmarks folder.

## Benchmark data

DynsbOD's evaluation uses data from two sources: The first is the wikipedia dynamic update dataset, which can be recreated as described above with the supplied `thesis.json` configuration for the wikibox parser.

The rest of the datasets can be obtained from the [HPI OD repeatability page](https://hpi.de/naumann/projects/repeatability/data-profiling/fds.html). The datasets have the same name on this site as in the thesis. The `ncvoter` dataset should be converted to utf-8 after download to avoid formatting errors. 

The HPI files further need to be preprocessed using the following command (executed within the datasets folder):

```bash
python3 <dataset>  --complete --convert-int --null_option nulls_first --sep="," --no-header
```

Adjust whether `--no-header` is used and what `--sep` to use depending on the dataset. For generating the `flights-reduced`, also add `--num-cols 12` and for `ncvoter-reduced` add `--num-cols 15`. To prevent executing the most expensive datasets for every experiment, move `ncvoter`, `flights`, `plista` and `horse` to a new folder called `complete_long`. The experiments are setup to only include this folder in some configurations.

