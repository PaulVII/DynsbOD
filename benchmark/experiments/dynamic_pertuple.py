from pathlib import Path
from benchmark.dynsbod_runner import FILE_PRESETS, run_experiments

# Load dynamic datasets (wikipedia events with updates)
# (duplicate to ensure disease, cpu are handled first)
wikipedia_dyn_configs = FILE_PRESETS["wikipedia_events"](exclude=["actor","single"]) + FILE_PRESETS["wikipedia_events"]()

# Test with efficient updates enabled and disabled
ALGO_CONFIGS = [
    {
        "useEfficientUpdates": True,
    },
    {
        "useEfficientUpdates": False,
    }
]

JAVA_OPTS_LIST = [
    ["-Xms2g", "-Xmx28g"],
]

if __name__ == "__main__":
    run_experiments(
        file_configs=wikipedia_dyn_configs,
        algo_configs=ALGO_CONFIGS,
        experiment_name="dynamic_pertuple",
        output_dir=Path("benchmark/dynsbod_results"),
        runs_per_combination=1,
        java_opts_list=JAVA_OPTS_LIST,
        per_tuple_timing=True  # Enable per-tuple timing tracking
    )
