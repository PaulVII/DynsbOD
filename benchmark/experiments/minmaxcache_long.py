from pathlib import Path
from benchmark.dynsbod_runner import FILE_PRESETS, run_experiments

# Load complete datasets with _long suffix
complete_long_configs = FILE_PRESETS["complete"](suffix='_long')

# Algorithm config: disable minmaxcache by setting threshold to 10 million
# Enable progress bar to see throughput
ALGO_CONFIGS = [
    {
        "minMaxCacheThreshold": 10000000,  # Effectively disable minmaxcache
        "showProgressBar": True
    }
]

JAVA_OPTS_LIST = [
    ["-Xms2g", "-Xmx28g"],
]

if __name__ == "__main__":
    run_experiments(
        file_configs=complete_long_configs,
        algo_configs=ALGO_CONFIGS,
        experiment_name="minmaxcache_long",
        output_dir=Path("benchmark/dynsbod_results"),
        runs_per_combination=1,
        java_opts_list=JAVA_OPTS_LIST,
        save_intermediate_stats=True,
        timeout_seconds=3600  # 1 hour timeout
    )
