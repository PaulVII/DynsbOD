from pathlib import Path
from benchmark.dynsbod_runner import FILE_PRESETS, run_experiments

complete_long_configs = FILE_PRESETS["complete"](suffix="_long")
complete_configs = FILE_PRESETS["complete"]()


letter_configs = [cfg for cfg in complete_configs if cfg.get("file_identifier") == "letter"]

horse_configs = [cfg for cfg in complete_long_configs if cfg.get("file_identifier") == "horse"]
flights_configs = [
    # comment out, since all further runs run into timeout
    # cfg for cfg in complete_long_configs if cfg.get("file_identifier", "").startswith("flights")
]

# wikipedia_dyn_configs = FILE_PRESETS["wikipedia_events"](exclude=["actor", "single"])
wikipedia_dyn_configs = []

if not horse_configs:
    raise ValueError("No horse dataset found in datasets/complete")
if not flights_configs:
    raise ValueError("No flights dataset found in datasets/complete_long")

all_file_configs = horse_configs + letter_configs + wikipedia_dyn_configs + flights_configs

ALGO_CONFIGS = [{}]

JAVA_OPTS_LIST = [
    ["-Xms2g", "-Xmx28g"],
    ["-Xms2g", "-Xmx16g"],
    ["-Xms2g", "-Xmx8g"],
    ["-Xms1g", "-Xmx4g"],
    ["-Xms512m", "-Xmx2g"],
    ["-Xms256m", "-Xmx1g"],
    ["-Xms128m", "-Xmx512m"],
    ["-Xms64m", "-Xmx256m"],
    ["-Xms28g", "-Xmx28g"],
    ["-Xms256m", "-Xmx28g"],


    # add more tests with different Xms?

    # ["-Xms12g", "-Xmx28g"],

]

if __name__ == "__main__":
    run_experiments(
        file_configs=all_file_configs,
        algo_configs=ALGO_CONFIGS,
        experiment_name="memory",
        output_dir=Path("benchmark/dynsbod_results"),
        runs_per_combination=2,
        java_opts_list=JAVA_OPTS_LIST,
        save_intermediate_stats=True,
    )
