from pathlib import Path
import subprocess
import json
import re
import logging
from typing import Dict, Any, List, Optional

logging.basicConfig(
    format="%(asctime)s %(levelname)s:%(message)s", 
    level=logging.INFO
)
logger = logging.getLogger("dynsbod-runner")

# Configuration
JAR_PATH = Path("target/scala-3.7.1/dynsbod-assembly-0.1.0-SNAPSHOT.jar")
DEFAULT_JAVA_OPTS = ["-Xms2g", "-Xmx28g"]
TIMEOUT_SECONDS = 4 * 60 * 60  # 4 hours

# File presets - each returns a list of dicts with full config (except algo)
# Each dict should have: data, results, csv, output
FILE_PRESETS = {
    "samples_initial": lambda: [
        {
            "data": {"initial": None, "increments": [str(f)]},
            "results": {"initial": None, "increments": []},
            "csv": {"delimiter": ",", "hasHeader": True, "canHaveDeletions": False},
            "output": f"./results/{f.parent.name}_{f.stem}.txt",
            "file_identifier": f"{f.parent.name}_{f.stem}",
        }
        for f in Path('datasets/samples').iterdir() 
        if f.is_file() and '_0_' in f.name and f.suffix == '.csv'
        and not "flights_20_500k_0_499999" in f.name 
    ],
    "complete": lambda suffix='': [
        {
            "data": {"initial": None, "increments": [str(f)]},
            "results": {"initial": None, "increments": []},
            "csv": {"delimiter": ";" if 'plista' in f.name or 'adult' in f.name or 'horse' in f.name else ',' , "hasHeader": True, "canHaveDeletions": False},
            "output": f"./results/{f.stem}_complete.txt",
            "file_identifier": f.stem,
        }
        for f in Path(f'datasets/complete{suffix}').iterdir() 
        if f.is_file() and f.suffix == '.csv'
    ],
    "wikipedia_baseline": lambda exclude = []: [
        {
            "data": {"initial": None, "increments": [str(f)]},
            "results": {"initial": None, "increments": []},
            "csv": {"delimiter": ",", "hasHeader": True, "canHaveDeletions": False},
            "output": f"./results/{f.parent.name}_{f.stem}.txt",
            "file_identifier": f"{f.parent.name}_{f.stem}",
        }
        for f in Path("datasets/wikipedia/dynfd").rglob("**/*.csv") 
        if (f.name.startswith('final_state'))
        and not 'marked' in f.name
        and f.parent.name not in exclude
    ],
    "wikipedia_events": lambda exclude = []: [
        {
            "data": {
              "increments": [
                str(f/"baseline_marked.csv"), 
                str(f/"events_with_updates.csv")
              ]
            },
            "results": {"initial": None, "increments": []},
            "csv": {"delimiter": ",", "hasHeader": True, "canHaveDeletions": True},
            "output": f"./results/{f.stem}_events_with_updates.txt",
            "file_identifier": f"{f.stem}_events_with_updates",
        }
        for f in Path("datasets/wikipedia/dynfd").iterdir()
        if f.is_dir()
        and f.name not in exclude
    ],
}


def build_config(base_config: Dict[str, Any], algo_cfg: Dict[str, Any], save_intermediate_stats: bool = False, stdout_path: Optional[Path] = None, per_tuple_timing: bool = False) -> str:
    """Build a config JSON string by combining base config with algo config"""
    config = dict(base_config)
    # Remove file_identifier if present (internal use only)
    config.pop("file_identifier", None)
    config["algo"] = algo_cfg
    if not "showProgressBar" in config["algo"]:
        config["algo"]["showProgressBar"] = True
    
    # Add intermediateStatsPath if requested
    if save_intermediate_stats and stdout_path:
        # Replace .txt extension with .csv for intermediate stats
        config["intermediateStatsPath"] = str(stdout_path.with_suffix('.intermediate.csv'))
    
    # Add perTupleTimingPath if requested
    if per_tuple_timing and stdout_path:
        # Replace .txt extension with .csv for per-tuple timing
        config["perTupleTimingPath"] = str(stdout_path.with_suffix('.pertuple.csv'))
    
    return json.dumps(config)


# Regex patterns for parsing stdout
_INCREMENT_RE = re.compile(r"Increment (\d+): Time = (\d+) ms, MaxMem = (\d+) MB")
_DELETION_RE = re.compile(r"Deletion (\d+): Time = (\d+) ms, MaxMem = (\d+) MB")
_INIT_RE = re.compile(r"Initialization took (\d+) ms")
_TOTAL_RE = re.compile(r"Total time: (\d+) ms")


def parse_stdout(stdout_text: str) -> Dict[str, Any]:
    """Parse metrics from stdout output"""
    metrics: Dict[str, Any] = {
        "init_ms": None,
        "total_ms": None,
        "increments": [],
        "deletions": [],
        "max_mem_mb": None,
    }
    max_mem = 0

    for line in stdout_text.splitlines():
        if m := _INIT_RE.search(line):
            metrics["init_ms"] = int(m.group(1))
        if m := _TOTAL_RE.search(line):
            metrics["total_ms"] = int(m.group(1))
        if m := _INCREMENT_RE.search(line):
            idx, t_ms, mem = int(m.group(1)), int(m.group(2)), int(m.group(3))
            metrics["increments"].append({"idx": idx, "time_ms": t_ms, "max_mem_mb": mem})
            max_mem = max(max_mem, mem)
        if m := _DELETION_RE.search(line):
            idx, t_ms, mem = int(m.group(1)), int(m.group(2)), int(m.group(3))
            metrics["deletions"].append({"idx": idx, "time_ms": t_ms, "max_mem_mb": mem})
            max_mem = max(max_mem, mem)

    metrics["max_mem_mb"] = max_mem or None
    return metrics


def load_metrics(path: Path) -> Dict[str, Any]:
    """Load metrics JSON file if it exists"""
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text())
    except Exception:
        return {}


def save_metrics(path: Path, data: Dict[str, Any]) -> None:
    """Save metrics to JSON file"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2))


def run_dynsbod_once(
    config_json: str,
    run_number: int,
    output_dir: Path,
    file_identifier: str,
    java_opts: Optional[List[str]] = None,
    stdout_path: Optional[Path] = None,
    timeout_seconds: Optional[int] = None,
) -> Dict[str, Any]:
    """
    Run dynsbod once and return metrics.
    Similar to run_hyod_benchmark from run_static_algorithm.py
    
    Args:
        config_json: JSON string to pass to dynsbod
        run_number: Run number (1-based)
        output_dir: Directory to store outputs
        file_identifier: Identifier for this file/config (used in filenames)
        java_opts: Java options (default: ["-Xms2g", "-Xmx11g"])
        timeout_seconds: Timeout in seconds (default: TIMEOUT_SECONDS)
    """
    java_opts = java_opts or DEFAULT_JAVA_OPTS
    timeout_seconds = timeout_seconds or TIMEOUT_SECONDS
    output_dir.mkdir(parents=True, exist_ok=True)
    
    # Only save stdout for runs that haven't been done yet
    if stdout_path is None:
        stdout_path = output_dir / f"{file_identifier}_run{run_number}_stdout.txt"
    
    timed_out = False
    
    try:
        # Check if this run was already completed
        if stdout_path.exists():
            logger.info(f"Using cached stdout for {file_identifier} run {run_number}")
            output_lines = stdout_path.read_text().splitlines()
        else:
            # Run with Popen and enforce timeout reliably
            cmd = ["java", *java_opts, "-jar", str(JAR_PATH), config_json]
            
            logger.info(f"Running dynsbod on {file_identifier} (run {run_number})...")
            logger.info(cmd)
            process = subprocess.Popen(
                cmd,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT
            )
            
            output_lines = []
            try:
                stdout_text, _ = process.communicate(timeout=timeout_seconds)
                output_lines = stdout_text.splitlines()
            except subprocess.TimeoutExpired:
                process.kill()
                stdout_text, _ = process.communicate()
                output_lines = stdout_text.splitlines()
                timed_out = True
                logger.warning(
                    f"Run {run_number} timed out after {timeout_seconds} seconds"
                )
            
            # Save stdout to file
            stdout_path.write_text(stdout_text)
        
        # Parse metrics from stdout
        metrics = parse_stdout('\n'.join(output_lines))
        if timed_out:
            metrics["error"] = f"Timeout after {timeout_seconds} seconds"
        metrics["stdout_file"] = str(stdout_path)
        
        return metrics
    
    except Exception as e:
        logger.error(f"Error on run {run_number}: {e}")
        return {
            "init_ms": None,
            "total_ms": None,
            "max_mem_mb": None,
            "error": str(e)
        }


def run_experiments(
    file_configs: List[Dict[str, Any]],
    algo_configs: List[Dict[str, Any]],
    experiment_name: str,
    output_dir: Path,
    runs_per_combination: int = 10,
    java_opts_list: Optional[List[List[str]]] = None,
    save_intermediate_stats: bool = False,
    skip_algo_configs: List[int] = [],
    timeout_seconds: Optional[int] = None,
    per_tuple_timing: bool = False,
) -> Dict[str, Any]:
    """
    Run dynsbod on all combinations of file configs, algo configs, and java opts.
    
    Args:
        file_configs: List of base configs from FILE_PRESETS
        algo_configs: List of algorithm configurations to test
        experiment_name: Name for this experiment (used in output paths)
        output_dir: Base output directory
        runs_per_combination: Number of runs per (file, algo, java) combination
        java_opts_list: List of Java option configurations (default: [DEFAULT_JAVA_OPTS])
        save_intermediate_stats: If True, save intermediate stats to CSV files
        skip_algo_configs: Used to skip old configs to preserve config IDs for now unused configs
        timeout_seconds: Timeout in seconds for each run (default: TIMEOUT_SECONDS)
        per_tuple_timing: If True, save per-tuple timing data to CSV files
    
    Returns:
        Dict with all metrics organized by combination
    """
    java_opts_list = java_opts_list or [DEFAULT_JAVA_OPTS]
    output_dir.mkdir(parents=True, exist_ok=True)
    metrics_path = output_dir / experiment_name / "benchmark_metrics.json"
    if metrics_path.exists():
        all_metrics = json.loads(metrics_path.read_text())
    else: 
        all_metrics = {}
    
    total_combinations = len(file_configs) * len(algo_configs) * len(java_opts_list)
    logger.info(f"Running {total_combinations} combinations x {runs_per_combination} runs each")
    
    for file_config in file_configs:
        for algo_idx, algo_cfg in enumerate(algo_configs):
            if algo_idx in skip_algo_configs:
                continue
            for java_idx, java_opts in enumerate(java_opts_list):
                file_identifier = file_config.get("file_identifier", "unknown")
                combo_key = f"algo{algo_idx}_java{java_idx}_{file_identifier}"

                # if combo_key in all_metrics.keys():
                #     logger.info(f"Skipping config {combo_key} as it already exists")
                #     continue
                
                logger.info(f"\n{'='*60}")
                logger.info(f"Processing: {combo_key}")
                logger.info(f"Algo: {algo_cfg}")
                logger.info(f"Java: {java_opts}")
                logger.info(f"{'='*60}")
                
                combo_metrics = []
                
                for run_num in range(1, runs_per_combination + 1):
                    stdout_path = output_dir / experiment_name / f"{combo_key}_run{run_num}_stdout.txt"
                    config_json = build_config(
                        file_config, 
                        algo_cfg, 
                        save_intermediate_stats=save_intermediate_stats,
                        stdout_path=stdout_path,
                        per_tuple_timing=per_tuple_timing
                    )
                    
                    metrics = run_dynsbod_once(
                        config_json=config_json,
                        run_number=run_num,
                        output_dir=output_dir / experiment_name,
                        file_identifier=combo_key,
                        timeout_seconds=timeout_seconds,
                        java_opts=java_opts,
                        stdout_path=stdout_path,
                    )
                    combo_metrics.append(metrics)
                
                all_metrics[combo_key] = combo_metrics
                
                # Save metrics after each combination completes
                save_metrics(metrics_path, all_metrics)
    
    logger.info(f"\nBenchmark complete! Results saved to {output_dir / experiment_name}")
    return all_metrics


if __name__ == "__main__":
    logger.info("This module is meant to be imported by experiment files.")
    logger.info("See benchmark/experiments/ for usage examples.")
