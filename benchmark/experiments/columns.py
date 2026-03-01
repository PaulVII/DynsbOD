import random
from pathlib import Path

from benchmark.dynsbod_runner import FILE_PRESETS, run_experiments
import pandas as pd

SEED = 42
random.seed(SEED)


flights_path = Path("datasets/complete_long/flights_20_500k.csv")
flights_columns_shuffled = pd.read_csv(flights_path, nrows=0).columns.tolist()
random.shuffle(flights_columns_shuffled)

plista_path = Path("datasets/complete_long/plista_1k.csv")
plista_columns_shuffled = pd.read_csv(plista_path, nrows=0, sep=";").columns.tolist()
random.shuffle(plista_columns_shuffled)

flight_configs = [
    {
            "data": {"initial": None, "increments": [str(flights_path)]},
            "results": {"initial": None, "increments": []},
            "csv": {"delimiter": ",", "hasHeader": True, "canHaveDeletions": False, "columnsToInclude": flights_columns_shuffled[:ncols]},
            "output": f"./results/flights_ncols_{ncols}.txt",
            "file_identifier": f"flights_ncols_{ncols}",
        }
        for ncols in [4,8,12,16,6,10,14,18] #num cols is 20, take results for 20 from somewhere else
]
plista_configs = [
    {
            "data": {"initial": None, "increments": [str(plista_path)]},
            "results": {"initial": None, "increments": []},
            "csv": {"delimiter": ";", "hasHeader": True, "canHaveDeletions": False, "columnsToInclude": plista_columns_shuffled[:ncols]},
            "output": f"./results/plista_ncols_{ncols}.txt",
            "file_identifier": f"plista_ncols_{ncols}",
        }
        for ncols in [16,32,48,8,24,40,56] # num cols is 63
]

# cpu has 15, disease has 13

ALGO_CONFIGS = [{}]

JAVA_CONFIGS = [["-Xms2G", "-Xmx28G"]]

if __name__ == "__main__":
    run_experiments(
        file_configs=flight_configs + plista_configs,
        java_opts_list=JAVA_CONFIGS,
        algo_configs=ALGO_CONFIGS,
        experiment_name="columns",
        output_dir=Path("benchmark/dynsbod_results"),
        runs_per_combination=3,
        save_intermediate_stats=True
    )
