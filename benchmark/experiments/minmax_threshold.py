from pathlib import Path
from benchmark.dynsbod_runner import FILE_PRESETS, run_experiments

# Load file presets (each returns list of full configs)
complete_config = FILE_PRESETS["complete"]()
# complete_long_configs = FILE_PRESETS["complete"](suffix='_long')
wikipedia_static_configs = FILE_PRESETS["wikipedia_baseline"]()
wikipedia_dyn_configs = FILE_PRESETS["wikipedia_events"](["actor","single"])


# Combine both presets
all_file_configs = complete_config + wikipedia_static_configs + wikipedia_dyn_configs

# Define multiple algorithm configurations to test
ALGO_CONFIGS = [{'minMaxCacheThreshold': t} for t in (0,2,5,10,20,50,100,200,500,1000000)]

JAVA_OPTS_LIST = [
    ["-Xms2g", "-Xmx28g"],
]

if __name__ == "__main__":
    run_experiments(
        file_configs=all_file_configs,
        algo_configs=ALGO_CONFIGS,
        experiment_name="minmax_threshold",
        output_dir=Path("benchmark/dynsbod_results"),
        runs_per_combination=3,
        java_opts_list=JAVA_OPTS_LIST,
        skip_algo_configs=[2,3,5,6,8]
    )
