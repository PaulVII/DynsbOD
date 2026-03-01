from pathlib import Path
import csv
from benchmark.dynsbod_runner import FILE_PRESETS, run_experiments


def split_csv_file(input_path: Path, delimiter: str, has_header: bool, file_identifier: str) -> tuple[Path, Path]:
    """
    Split a CSV file in half and return paths to the two halves.
    Creates temporary files with _first_half and _second_half suffixes.
    """
    with open(input_path, 'r', encoding='utf-8') as f:
        reader = csv.reader(f, delimiter=delimiter)
        
        # Read all rows
        if has_header:
            header = next(reader)
            rows = list(reader)
        else:
            rows = list(reader)
            header = None
    
    # Split rows in half
    mid_point = len(rows) // 2
    first_half_rows = rows[:mid_point]
    second_half_rows = rows[mid_point:]
    
    # Create temporary file paths
    temp_dir = Path("datasets/temp_halves")
    temp_dir.mkdir(parents=True, exist_ok=True)
    
    first_half_path = temp_dir / f"{file_identifier}_first_half.csv"
    second_half_path = temp_dir / f"{file_identifier}_second_half.csv"
    
    # Write first half
    with open(first_half_path, 'w', encoding='utf-8', newline='') as f:
        writer = csv.writer(f, delimiter=delimiter)
        if header:
            writer.writerow(header)
        writer.writerows(first_half_rows)
    
    # Write second half
    with open(second_half_path, 'w', encoding='utf-8', newline='') as f:
        writer = csv.writer(f, delimiter=delimiter)
        if header:
            writer.writerow(header)
        writer.writerows(second_half_rows)
    
    return first_half_path, second_half_path


def create_halved_configs(file_configs: list) -> list:
    """
    For each file config, create two new configs:
    - _first_half: runs on first half of dataset
    - _second_half: runs on second half, using first half results as initial state
    
    Returns a list with all the halved configs.
    """
    halved_configs = []
    
    for config in file_configs:
        file_identifier = config.get("file_identifier", "unknown")
        if file_identifier == 'letter':
            file_identifier = 'letter_laptop'
        csv_config = config["csv"]
        delimiter = csv_config["delimiter"]
        has_header = csv_config["hasHeader"]
        
        # Get the input file path (assuming single increment for complete datasets)
        increments = config["data"]["increments"]
        if len(increments) != 1:
            print(f"Skipping {file_identifier} - multiple increments not supported for halving")
            continue
        
        input_file_path = Path(increments[0])
        
        # Split the CSV file
        first_half_path, second_half_path = split_csv_file(
            input_file_path, 
            delimiter, 
            has_header,
            file_identifier
        )
        
        # Create first half config
        first_half_config = {
            "data": {"initial": None, "increments": [str(first_half_path)]},
            "results": {"initial": None, "increments": []},
            "csv": csv_config.copy(),
            "output": f"./results/{file_identifier}_first_half.txt",
            "file_identifier": f"{file_identifier}_first_half",
        }
        
        # Create second half config - uses first half results as initial
        second_half_config = {
            "data": {"initial": str(first_half_path), "increments": [str(second_half_path)]},
            "results": {"initial": f"./results/{file_identifier}_first_half.txt", "increments": []},
            "csv": csv_config.copy(),
            "output": f"./results/{file_identifier}_second_half.txt",
            "file_identifier": f"{file_identifier}_second_half",
        }
        
        halved_configs.append(first_half_config)
        halved_configs.append(second_half_config)
    
    return halved_configs


# Load file presets (each returns list of full configs)
complete_easy_configs = FILE_PRESETS["complete"]()
complete_slow_configs = FILE_PRESETS["complete"](suffix='_long')

# wikipedia_dyn_configs = FILE_PRESETS["wikipedia_events"](exclude=["actor","single"])
wikipedia_static_configs = FILE_PRESETS["wikipedia_baseline"]()

# Combine both presets
all_file_configs = complete_easy_configs + complete_slow_configs + wikipedia_static_configs

# Create halved versions of all configs
all_halved_configs = create_halved_configs(all_file_configs)

# default config
ALGO_CONFIGS = [{}]

# print(all_file_configs)
# print(all_halved_configs)
# exit(0)

JAVA_OPTS_LIST = [
    ["-Xms2g", "-Xmx8g"],
]

if __name__ == "__main__":
    run_experiments(
        file_configs=all_halved_configs + all_file_configs,
        algo_configs=ALGO_CONFIGS,
        experiment_name="intermediate",
        output_dir=Path("benchmark/dynsbod_results"),
        runs_per_combination=1,
        java_opts_list=JAVA_OPTS_LIST,
        save_intermediate_stats=True
    )
