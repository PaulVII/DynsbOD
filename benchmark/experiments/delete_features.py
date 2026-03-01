from pathlib import Path
from benchmark.dynsbod_runner import FILE_PRESETS, run_experiments

# Load file presets (each returns list of full configs)
complete_easy_configs = FILE_PRESETS["complete"]()
# complete_slow_configs = FILE_PRESETS["complete"](suffix='_long')

wikipedia_dyn_configs = FILE_PRESETS["wikipedia_events"](exclude=["actor","single"])
# wikipedia_dyn_configs = []
wikipedia_static_configs = FILE_PRESETS["wikipedia_baseline"](exclude=["actor","single"])

JAVA_OPTS_LIST = [
    ["-Xms2g", "-Xmx28g"],
]

# base configs: have one version for all combinations of 
STATIC_ALGO_CONFIGS = []
DYNAMIC_ALGO_CONFIGS = []  # only default config for dynamic datasets
for optimizeBuildOrder in [True, False]:
    for useLazyList in [True, False]:
        for deletionMode in ["deleteonly", "afterinsert"]:
            STATIC_ALGO_CONFIGS.append({
                "optimizeBuildOrder": optimizeBuildOrder,
                "useLazyList": useLazyList,
                "deletionMode": deletionMode
            })
        for useEfficientUpdates in [True, False]:
            DYNAMIC_ALGO_CONFIGS.append({
                "optimizeBuildOrder": optimizeBuildOrder,
                "useLazyList": useLazyList,
                "useEfficientUpdates": useEfficientUpdates
            })

# add one run each with default config but skipRevalidationByInsert
STATIC_ALGO_CONFIGS.append({"skipRevalidationByInsert": True, "deletionMode": "afterinsert"})
DYNAMIC_ALGO_CONFIGS.append({"skipRevalidationByInsert": True})


if __name__ == "__main__":
    # dynamic datasets
    run_experiments(
        file_configs=wikipedia_dyn_configs,
        algo_configs=DYNAMIC_ALGO_CONFIGS,
        experiment_name="delete_features_dynamic",
        output_dir=Path("benchmark/dynsbod_results"),
        runs_per_combination=3,
        java_opts_list=JAVA_OPTS_LIST,
        save_intermediate_stats=True
    )
    # static datasets
    run_experiments(
        file_configs=wikipedia_static_configs + complete_easy_configs,
        algo_configs=STATIC_ALGO_CONFIGS,
        experiment_name="delete_features_static",
        output_dir=Path("benchmark/dynsbod_results"),
        runs_per_combination=3,
        java_opts_list=JAVA_OPTS_LIST,
        save_intermediate_stats=True
    )
