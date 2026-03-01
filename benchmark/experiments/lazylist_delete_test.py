from pathlib import Path
from benchmark.dynsbod_runner import run_experiments

# Configuration for fd-reduced-30 dataset
# Start with full baseline from intermediate run (second half)
# Then delete everything to test deletion performance
FD_REDUCED_CONFIG = {
    "data": {"initial": None, "increments": ["datasets/complete_long/fd-reduced-30.csv"]},
    "results": {"initial": None, "increments": ["./results/fd-reduced-30_second_half.txt"]},
    "csv": {"delimiter": ",", "hasHeader": True, "canHaveDeletions": False},
    "output": "./results/fd-reduced-30_lazylist_delete_test.txt",
    "file_identifier": "fd-reduced-30",
}

# Test with lazylist optimization on and off
# deletionMode: "deleteonly" starts with full baseline and deletes all rows
# This tests how far deletions get in 1 hour with existing OD results
ALGO_CONFIGS = [
    {
        "useLazyList": True,
        "deletionMode": "deleteonly",
        "showProgressBar": True,
        "skipRevalidationByInsert": True
    },
    {
        "useLazyList": False,
        "deletionMode": "deleteonly", 
        "showProgressBar": True,
        "skipRevalidationByInsert": True

    }
]

JAVA_OPTS_LIST = [
    ["-Xms2g", "-Xmx28g"],
]

if __name__ == "__main__":
    run_experiments(
        file_configs=[FD_REDUCED_CONFIG],
        algo_configs=ALGO_CONFIGS,
        experiment_name="lazylist_delete_test",
        output_dir=Path("benchmark/dynsbod_results"),
        runs_per_combination=1,
        java_opts_list=JAVA_OPTS_LIST,
        save_intermediate_stats=True,
        timeout_seconds=3600  # 1 hour timeout
    )
