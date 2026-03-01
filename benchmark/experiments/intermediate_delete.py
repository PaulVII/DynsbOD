from pathlib import Path
from benchmark.dynsbod_runner import FILE_PRESETS, run_experiments


def create_delete_configs_from_halves(file_configs: list, use_second_half: bool = True) -> list:
    """
    Create configs for delete experiments using results from intermediate halves.
    
    Args:
        file_configs: Original file configs (e.g., from FILE_PRESETS["complete"])
        use_second_half: If True, use second_half results/data. If False, use first_half.
    
    Returns:
        List of configs suitable for deleteonly experiments with initial results.
    """
    delete_configs = []
    
    for config in file_configs:
        file_identifier = config.get("file_identifier", "unknown")
        csv_config = config["csv"]
        
        # Determine which half to use
        half_suffix = "_second_half" if use_second_half else "_first_half"
        
        # Path to the halved data file in temp_halves
        if use_second_half:
            data_path = config["data"]["increments"][0]  # original full data path
        else:
            data_path = Path(f"datasets/temp_halves/{file_identifier}_first_half.csv")
            # Check if files exist (they should be created by intermediate.py)
            if not data_path.exists():
                print(f"Warning: Skipping {file_identifier}{half_suffix} - data file not found: {data_path}")
                continue
            data_path = str(data_path)
        
        # Path to the results from the intermediate experiment
        results_path = f"./results/{file_identifier}{half_suffix}.txt"
        
        if not Path(results_path).exists():
            print(f"Warning: Skipping {file_identifier}{half_suffix} - results file not found: {results_path}")
            continue

        if use_second_half:
            file_identifier = file_identifier
        else:
            file_identifier = f"{file_identifier}_first_half"
        
        # Create delete config
        # For deleteonly, we need:
        # - data: the same data that was used to create the results
        # - results: the results file from the intermediate run
        delete_config = {
            "data": {"initial": None, "increments": [data_path]},
            "results": {"initial": None, "increments": [results_path]},
            "csv": csv_config.copy(),
            "output": f"./results/{file_identifier}{half_suffix}_delete.txt",
            "file_identifier": file_identifier,
        }
        
        delete_configs.append(delete_config)
    
    return delete_configs


# Load file presets (each returns list of full configs)
complete_easy_configs = FILE_PRESETS["complete"]()
complete_slow_configs = FILE_PRESETS["complete"](suffix='_long')

wikipedia_static_configs = FILE_PRESETS["wikipedia_baseline"]()
# wikipedia_dyn_configs = FILE_PRESETS["wikipedia_events"](exclude=["actor","single"])
wikipedia_dyn_configs = []


JAVA_OPTS_LIST = [
    ["-Xms2g", "-Xmx28g"],
]

if __name__ == "__main__":
    delete_configs_second_half = create_delete_configs_from_halves(
    complete_easy_configs + wikipedia_static_configs + complete_slow_configs,
    use_second_half=True
)

    delete_configs_first_half = create_delete_configs_from_halves(
        complete_easy_configs + wikipedia_static_configs + complete_slow_configs, 
        use_second_half=False
    )

    # Combine all delete configs
    delete_configs_cheap = create_delete_configs_from_halves(
        complete_easy_configs + wikipedia_static_configs,
        use_second_half=True
    ) + create_delete_configs_from_halves(
        complete_easy_configs + wikipedia_static_configs, 
        use_second_half=False
    )

    delete_configs_expensive = create_delete_configs_from_halves(
        complete_slow_configs,
        use_second_half=True
    )
    print(f"Found {len(delete_configs_second_half)} configs for second_half deletions")
    print(f"Found {len(delete_configs_first_half)} configs for first_half deletions")
    # print(f"Total: {len(all_delete_configs)} delete configs")
    
    # Run delete experiments
    run_experiments(
        file_configs=complete_easy_configs,
        algo_configs=[{"deletionMode": "deleteonly"}, {"deletionMode": "afterinsert"}],
        experiment_name="intermediate_delete",
        output_dir=Path("benchmark/dynsbod_results"),
        runs_per_combination=2,
        java_opts_list=JAVA_OPTS_LIST,
        save_intermediate_stats=True
    )

    run_experiments(
        file_configs=wikipedia_dyn_configs,
        algo_configs=[{}],
        experiment_name="intermediate_dynamic",
        output_dir=Path("benchmark/dynsbod_results"),
        runs_per_combination=2,
        java_opts_list=JAVA_OPTS_LIST,
        save_intermediate_stats=True
    )

    run_experiments(
        file_configs=complete_slow_configs,
        algo_configs=[{"deletionMode": "deleteonly"}],
        experiment_name="intermediate_delete",
        output_dir=Path("benchmark/dynsbod_results"),
        runs_per_combination=1,
        java_opts_list=JAVA_OPTS_LIST,
        save_intermediate_stats=True
    )
