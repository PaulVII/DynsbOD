from pathlib import Path
import subprocess
import json
import tempfile
import logging
import pandas as pd
from typing import Dict, Any, List, Optional
import re

logging.basicConfig(
    format="%(asctime)s %(levelname)s:%(message)s",
    level=logging.INFO
)
logger = logging.getLogger("dynfd-runner")

# Configuration
# custom version with CLI access found at 
JAR_PATH = Path("benchmark/dynfd.jar")
# last opt needed to run dynfd on java11+
DEFAULT_JAVA_OPTS = ["-Xms2g", "-Xmx28g"]
TIMEOUT_SECONDS = 4 * 60 * 60  # 4 hours
BATCH_SIZES = [1, 10, 100, 1000]
RUNS_PER_CONFIG = 3


def get_separator(file_path: str) -> str:
    """Determine the CSV separator based on the file name"""
    file_name = Path(file_path).name.lower()
    if any(keyword in file_name for keyword in ['plista', 'adult', 'horse']):
        return ';'
    return ','


def prepare_wikipedia_dynamic_batch(events_without_updates_path: Path, separator: str) -> Path:
    """
    Prepare the batch file for Wikipedia dynamic datasets:
    1. Load events_without_updates.csv
    2. Rename event_type column to ::action
    3. Remove articleid column
    4. Save to temporary file
    """
    df = pd.read_csv(events_without_updates_path, sep=separator)
    
    # Rename event_type to ::action
    if 'event_type' in df.columns:
        df = df.rename(columns={'event_type': '::action'})
    
    # Remove articleid column
    if 'articleid' in df.columns:
        df = df.drop(columns=['articleid'])
    
    temp_file = Path(tempfile.mktemp(suffix='.csv'))
    df.to_csv(temp_file, sep=separator, index=False)
    logger.info(f"Created Wikipedia dynamic batch: {temp_file}")
    return temp_file


def get_wikipedia_dynamic_baseline(folder: Path) -> Path:
    """
    Get the baseline file for Wikipedia dynamic datasets by removing _marked suffix
    """
    baseline_marked = folder / "baseline_marked.csv"
    baseline = folder / "baseline.csv"
    
    if not baseline.exists() and baseline_marked.exists():
        logger.info(f"Using baseline.csv instead of baseline_marked.csv")
    
    return baseline


def parse_dynfd_output(output_text: str) -> Dict[str, Any]:
    """
    Parse DynFD output to extract metrics
    Expected format:
    ================================================================================
    RESULTS
    ================================================================================
    Initial runtime:           828 ms
    Incremental runtime:       586 ms
    Total runtime:             1414 ms
    Max memory used:           198,97 MB
    Initial FD count:          88
    Final FD count:            96
    Validations performed:     24
    Validations pruned:        799
    ================================================================================
    """
    metrics = {
        "initial_runtime_ms": None,
        "incremental_runtime_ms": None,
        "total_runtime_ms": None,
        "max_memory_mb": None,
        "initial_fd_count": None,
        "final_fd_count": None,
        "validations_performed": None,
        "validations_pruned": None,
    }
    
    for line in output_text.splitlines():
        line = line.strip()
        if "Initial runtime:" in line:
            match = re.search(r'(\d+)\s*ms', line)
            if match:
                metrics["initial_runtime_ms"] = int(match.group(1))
        elif "Incremental runtime:" in line:
            match = re.search(r'(\d+)\s*ms', line)
            if match:
                metrics["incremental_runtime_ms"] = int(match.group(1))
        elif "Total runtime:" in line:
            match = re.search(r'(\d+)\s*ms', line)
            if match:
                metrics["total_runtime_ms"] = int(match.group(1))
        elif "Max memory used:" in line:
            match = re.search(r'([\d,\.]+)\s*MB', line)
            if match:
                # Handle both comma and dot as decimal separator
                mem_str = match.group(1).replace(',', '.')
                metrics["max_memory_mb"] = float(mem_str)
        elif "Initial FD count:" in line:
            match = re.search(r'(\d+)', line)
            if match:
                metrics["initial_fd_count"] = int(match.group(1))
        elif "Final FD count:" in line:
            match = re.search(r'(\d+)', line)
            if match:
                metrics["final_fd_count"] = int(match.group(1))
        elif "Validations performed:" in line:
            match = re.search(r'(\d+)', line)
            if match:
                metrics["validations_performed"] = int(match.group(1))
        elif "Validations pruned:" in line:
            match = re.search(r'(\d+)', line)
            if match:
                metrics["validations_pruned"] = int(match.group(1))
    
    return metrics


def run_dynfd_once(
    baseline_path: Path,
    batch_path: Path,
    batch_size: int,
    separator: str,
    dataset_name: str,
    run_number: int,
    output_dir: Path,
    java_opts: Optional[List[str]] = None,
) -> Dict[str, Any]:
    """
    Run DynFD once and return metrics
    
    Args:
        baseline_path: Path to baseline CSV file
        batch_path: Path to batch/increment CSV file
        batch_size: Batch size to use
        separator: CSV separator
        dataset_name: Name of the dataset (for identification)
        run_number: Run number (1-based)
        output_dir: Directory to store outputs
        temp_files: List to track temporary files for cleanup
        java_opts: Java options (default: DEFAULT_JAVA_OPTS)
    """
    java_opts = java_opts or DEFAULT_JAVA_OPTS
    output_dir.mkdir(parents=True, exist_ok=True)
    
    stdout_path = output_dir / f"{dataset_name}_batch{batch_size}_run{run_number}_stdout.txt"
    
    timed_out = False
    
    try:
        # Check if this run was already completed
        if stdout_path.exists():
            logger.info(f"Using cached output for {dataset_name} batch size {batch_size} run {run_number}")
            output_text = stdout_path.read_text()
        else:
            # # Build command
            cmd = [
                "java",
                *java_opts,
                "-jar",
                str(JAR_PATH),
                str(baseline_path),
                str(batch_path),
                "--batch-size", str(batch_size),
                "--separator", separator,
            ]
            
            logger.info(f"Running DynFD on {dataset_name} (batch size {batch_size}, run {run_number})...")
            logger.info(f"Command: {' '.join(cmd)}")
            output_text = ""
            process = subprocess.Popen(
                cmd,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT
            )
            
            try:
                output_text, _ = process.communicate(timeout=TIMEOUT_SECONDS)
            except subprocess.TimeoutExpired:
                process.kill()
                output_text, _ = process.communicate()
                timed_out = True
                logger.warning(
                    f"Run {run_number} timed out after {TIMEOUT_SECONDS} seconds"
                )
            
            # Save stdout to file
            stdout_path.write_text(output_text)
        

        # Parse metrics from output
        metrics = parse_dynfd_output(output_text)
        if timed_out:
            metrics["error"] = f"Timeout after {TIMEOUT_SECONDS} seconds"
        metrics["stdout_file"] = str(stdout_path)
        
        return metrics
    
    except Exception as e:
        logger.error(f"Error on run {run_number}: {e}")
        return {
            "initial_runtime_ms": None,
            "incremental_runtime_ms": None,
            "total_runtime_ms": None,
            "max_memory_mb": None,
            "error": str(e)
        }


def run_static_dataset(
    dataset_path: Path,
    dataset_name: str,
    separator: str,
    output_dir: Path,
    num_runs: int = RUNS_PER_CONFIG
) -> Dict[str, List[Dict[str, Any]]]:
    """
    Run DynFD on a static dataset (complete, complete_long, wikipedia static)
    Runs both insert and delete modes
    """
    results = {}
    
    # Run insert mode
    for batch_size in BATCH_SIZES:
        logger.info(f"{'='*60}")
        logger.info(f"Dataset: {dataset_name}, Mode: INSERT, Batch size: {batch_size}")
        logger.info(f"{'='*60}")
        
        batch_metrics = []
        
        for run_num in range(1, num_runs + 1):
            metrics = run_dynfd_once(
                baseline_path=Path("insert"),  # Empty baseline, insert all
                batch_path=dataset_path,
                batch_size=batch_size,
                separator=separator,
                dataset_name=f"{dataset_name}",
                run_number=run_num,
                output_dir=output_dir,
            )
            batch_metrics.append(metrics)
        
        results[f"insert_batch_{batch_size}"] = batch_metrics
    
    # Run delete mode
    for batch_size in BATCH_SIZES:
        logger.info(f"{'='*60}")
        logger.info(f"Dataset: {dataset_name}, Mode: DELETE, Batch size: {batch_size}")
        logger.info(f"{'='*60}")
        
        batch_metrics = []
        
        for run_num in range(1, num_runs + 1):
            metrics = run_dynfd_once(
                baseline_path=Path("delete"),  # Full dataset baseline, delete all
                batch_path=dataset_path,
                batch_size=batch_size,
                separator=separator,
                dataset_name=f"{dataset_name}_delete",
                run_number=run_num,
                output_dir=output_dir,
            )
            batch_metrics.append(metrics)
        
        results[f"delete_batch_{batch_size}"] = batch_metrics
    
    return results


def run_wikipedia_dynamic_dataset(
    dataset_folder: Path,
    dataset_name: str,
    separator: str,
    output_dir: Path,
    num_runs: int = RUNS_PER_CONFIG
) -> Dict[str, List[Dict[str, Any]]]:
    """
    Run DynFD on a Wikipedia dynamic dataset
    Uses baseline.csv (not baseline_marked.csv) and processes events_without_updates.csv
    """
    results = {}
    temp_files = []
    
    try:
        # Get baseline file
        baseline_path = get_wikipedia_dynamic_baseline(dataset_folder)
        
        if not baseline_path.exists():
            logger.error(f"Baseline file not found: {baseline_path}")
            return {}
        
        # Prepare batch file from events_without_updates.csv
        events_without_updates_path = dataset_folder / "events_without_updates.csv"
        
        if not events_without_updates_path.exists():
            logger.error(f"Events file not found: {events_without_updates_path}")
            return {}
        
        batch_path = prepare_wikipedia_dynamic_batch(events_without_updates_path, separator)
        temp_files.append(batch_path)
        
        for batch_size in BATCH_SIZES:
            logger.info(f"\n{'='*60}")
            logger.info(f"Dataset: {dataset_name}, Batch size: {batch_size}")
            logger.info(f"{'='*60}")
            
            batch_metrics = []
            
            for run_num in range(1, num_runs + 1):
                metrics = run_dynfd_once(
                    baseline_path=baseline_path,
                    batch_path=batch_path,
                    batch_size=batch_size,
                    separator=separator,
                    dataset_name=dataset_name,
                    run_number=run_num,
                    output_dir=output_dir,
                )
                batch_metrics.append(metrics)
            
            results[f"batch_{batch_size}"] = batch_metrics
    
    finally:
        # Clean up temporary files
        for temp_file in temp_files:
            try:
                if temp_file.exists():
                    temp_file.unlink()
                    logger.debug(f"Cleaned up temp file: {temp_file}")
            except Exception as e:
                logger.warning(f"Failed to clean up temp file {temp_file}: {e}")
    
    return results


def run_all_experiments():
    """
    Run DynFD on all datasets:
    - complete
    - complete_long
    - wikipedia static (final_state.csv files)
    - wikipedia dynamic (events_without_updates.csv files)
    """
    output_dir = Path("benchmark/dynfd_results")
    output_dir.mkdir(parents=True, exist_ok=True)
    
    metrics_path = output_dir / "benchmark_metrics.json"
    if metrics_path.exists():
        all_metrics = json.loads(metrics_path.read_text())
    else:
        all_metrics = {}

    
    # 4. Process Wikipedia dynamic datasets
    logger.info("\n" + "="*80)
    logger.info("PROCESSING WIKIPEDIA DYNAMIC DATASETS")
    logger.info("="*80)
    
    wikipedia_dir = Path("datasets/wikipedia/dynfd")
    if wikipedia_dir.exists():
        for folder in sorted(wikipedia_dir.iterdir()):
            if not folder.is_dir():
                continue
            
            dataset_name = f"wikipedia_dynamic_{folder.name}"
            
            # if dataset_name in all_metrics:
            #     logger.info(f"Skipping {dataset_name} (already processed)")
            #     continue
            
            separator = ','  # Wikipedia files use comma
            logger.info(f"\nProcessing: {folder.name} (dynamic, separator: '{separator}')")
            
            results = run_wikipedia_dynamic_dataset(
                dataset_folder=folder,
                dataset_name=dataset_name,
                separator=separator,
                output_dir=output_dir,
                num_runs=1
            )
            
            if results:  # Only save if we got results
                all_metrics[dataset_name] = results
                metrics_path.write_text(json.dumps(all_metrics, indent=2))
    
    # 1. Process complete datasets
    logger.info("\n" + "="*80)
    logger.info("PROCESSING COMPLETE DATASETS")
    logger.info("="*80)
    
    complete_dir = Path("datasets/complete")
    if complete_dir.exists():
        for csv_file in sorted(complete_dir.glob("*.csv")):
            dataset_name = f"complete_{csv_file.stem}"
            
            # if dataset_name in all_metrics:
            #     logger.info(f"Skipping {dataset_name} (already processed)")
            #     continue
            
            separator = get_separator(str(csv_file))
            logger.info(f"\nProcessing: {csv_file.name} (separator: '{separator}')")
            
            results = run_static_dataset(
                dataset_path=csv_file,
                dataset_name=dataset_name,
                separator=separator,
                output_dir=output_dir,
            )
            
            all_metrics[dataset_name] = results
            metrics_path.write_text(json.dumps(all_metrics, indent=2))
    
    # 3. Process Wikipedia static datasets (final_state.csv)
    logger.info("\n" + "="*80)
    logger.info("PROCESSING WIKIPEDIA STATIC DATASETS")
    logger.info("="*80)
    
    if wikipedia_dir.exists():
        for folder in sorted(wikipedia_dir.iterdir()):
            if not folder.is_dir():
                logger.error(f"{folder} is not a directory")
                continue
            
            final_state_file = folder / "final_state.csv"
            if not final_state_file.exists():
                logger.error(f"{final_state_file} does not exist")
                continue
            
            dataset_name = f"wikipedia_static_{folder.name}"
            
            # if dataset_name in all_metrics:
            #     logger.info(f"Skipping {dataset_name} (already processed)")
            #     continue
            
            separator = get_separator(str(final_state_file))
            logger.info(f"\nProcessing: {folder.name}/final_state.csv (separator: '{separator}')")
            
            results = run_static_dataset(
                dataset_path=final_state_file,
                dataset_name=dataset_name,
                separator=separator,
                output_dir=output_dir,
            )
            
            all_metrics[dataset_name] = results
            metrics_path.write_text(json.dumps(all_metrics, indent=2))
    # 2. Process complete_long datasets
    logger.info("\n" + "="*80)
    logger.info("PROCESSING COMPLETE_LONG DATASETS")
    logger.info("="*80)
    
    complete_long_dir = Path("datasets/complete_long")
    if complete_long_dir.exists():
        for csv_file in sorted(complete_long_dir.glob("*.csv")):
            dataset_name = f"complete_long_{csv_file.stem}"
            
            # if dataset_name in all_metrics:
            #     logger.info(f"Skipping {dataset_name} (already processed)")
            #     continue
            
            separator = get_separator(str(csv_file))
            logger.info(f"\nProcessing: {csv_file.name} (separator: '{separator}')")
            
            results = run_static_dataset(
                dataset_path=csv_file,
                dataset_name=dataset_name,
                separator=separator,
                output_dir=output_dir,
                num_runs=1
            )
            
            all_metrics[dataset_name] = results
            metrics_path.write_text(json.dumps(all_metrics, indent=2))

    
    logger.info("\n" + "="*80)
    logger.info("BENCHMARK COMPLETE!")
    logger.info(f"Results saved to: {metrics_path}")
    logger.info("="*80)
    
    return all_metrics


if __name__ == "__main__":
    run_all_experiments()
