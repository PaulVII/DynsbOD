from pathlib import Path
import subprocess
import json
import tempfile
import logging
import pandas as pd
from typing import Dict, Any, List, Optional
import re

NUM_ROWS_IN_BASELINE = 10

logging.basicConfig(
    format="%(asctime)s %(levelname)s:%(message)s",
    level=logging.DEBUG
)
logger = logging.getLogger("incpod-runner")

# Configuration
HYDRA_JAR_PATH = Path("benchmark/Hydra+.jar")
INCPOD_JAR_PATH = Path("benchmark/IncPOD.jar")
DEFAULT_JAVA_OPTS = ["-Xms2g", "-Xmx28g"]
TIMEOUT_SECONDS = 4 * 60 * 60  # 4 hours
RUNS_PER_CONFIG = 3


def get_separator(file_path: str) -> str:
    """Determine the CSV separator based on the file name"""
    file_name = Path(file_path).name.lower()
    if any(keyword in file_name for keyword in ['plista', 'adult', 'horse']):
        return ';'
    return ','


def count_csv_rows(csv_path: Path, separator: str) -> int:
    """Count the number of data rows in a CSV file (excluding header)"""
    with open(csv_path, 'r') as f:
        df = pd.read_csv(f, sep=separator)
        return len(df)



def create_baseline_with_one_row(csv_path: Path, separator: str) -> Path:
    """Create a temporary file with just the CSV headers and first data row"""
    df = pd.read_csv(csv_path, sep=separator, nrows=NUM_ROWS_IN_BASELINE)
    temp_file = Path(tempfile.mktemp(suffix='.csv'))
    df.to_csv(temp_file, sep=separator, index=False)
    logger.debug(f"Created baseline with one row: {temp_file}")
    return temp_file


def create_increment_without_first_row(csv_path: Path, separator: str) -> Path:
    """Create a temporary file with all data rows except the first one"""
    df = pd.read_csv(csv_path, sep=separator)
    df_increment = df.iloc[NUM_ROWS_IN_BASELINE:]  # Skip first row
    temp_file = Path(tempfile.mktemp(suffix='.csv'))
    df_increment.to_csv(temp_file, sep=separator, index=False)
    logger.debug(f"Created increment without first row: {temp_file}")
    return temp_file


def parse_hydra_output(output_text: str) -> Dict[str, Any]:
    """
    Parse Hydra+ output to extract metrics
    Look for timing and POD count information
    """
    metrics = {
        "runtime_ms": None,
        "pod_count": None,
        "error": None
    }
    
    # Common patterns to look for
    for line in output_text.splitlines():
        line = line.strip()
        
        # Look for time patterns (ms or seconds)
        if "time" in line.lower() or "runtime" in line.lower():
            # Try to extract milliseconds
            match = re.search(r'(\d+)\s*ms', line, re.IGNORECASE)
            if match:
                metrics["runtime_ms"] = int(match.group(1))
            else:
                # Try seconds
                match = re.search(r'([\d.]+)\s*s(?:ec)?', line, re.IGNORECASE)
                if match:
                    metrics["runtime_ms"] = int(float(match.group(1)) * 1000)
        
        # Look for POD/OD count
        if "pod" in line.lower() or "od" in line.lower():
            match = re.search(r'(\d+)', line)
            if match and metrics["pod_count"] is None:
                metrics["pod_count"] = int(match.group(1))
    
    return metrics


def parse_incpod_output(output_text: str) -> Dict[str, Any]:
    """
    Parse IncPOD output to extract metrics
    Look for timing and POD count information
    """
    metrics = {
        "runtime_ms": None,
        "pod_count": None,
        "incremental_runtime_ms": None,
        "error": None
    }
    
    # Common patterns to look for
    for line in output_text.splitlines():
        line = line.strip()
        
        # Look for time patterns
        if "time" in line.lower() or "runtime" in line.lower():
            # Try to extract milliseconds
            match = re.search(r'(\d+)\s*ms', line, re.IGNORECASE)
            if match:
                if "incremental" in line.lower() or "inc" in line.lower():
                    metrics["incremental_runtime_ms"] = int(match.group(1))
                else:
                    metrics["runtime_ms"] = int(match.group(1))
            else:
                # Try seconds
                match = re.search(r'([\d.]+)\s*s(?:ec)?', line, re.IGNORECASE)
                if match:
                    runtime_ms = int(float(match.group(1)) * 1000)
                    if "incremental" in line.lower() or "inc" in line.lower():
                        metrics["incremental_runtime_ms"] = runtime_ms
                    else:
                        metrics["runtime_ms"] = runtime_ms
        
        # Look for POD/OD count
        if "pod" in line.lower() or "od" in line.lower():
            match = re.search(r'(\d+)', line)
            if match and metrics["pod_count"] is None:
                metrics["pod_count"] = int(match.group(1))
    
    return metrics


def run_hydra_once(
    baseline_path: Path,
    output_pods_path: Path,
    num_rows: int,
    dataset_name: str,
    run_number: int,
    output_dir: Path,
    temp_files: List[Path],
    java_opts: Optional[List[str]] = None,
) -> Dict[str, Any]:
    """
    Run Hydra+ once and return metrics
    
    Args:
        baseline_path: Path to baseline CSV file
        output_pods_path: Path where Hydra+ will write discovered PODs
        num_rows: Number of rows in baseline
        dataset_name: Name of the dataset (for identification)
        run_number: Run number (1-based)
        output_dir: Directory to store outputs
        temp_files: List to track temporary files for cleanup
        java_opts: Java options (default: DEFAULT_JAVA_OPTS)
    """
    java_opts = java_opts or DEFAULT_JAVA_OPTS
    output_dir.mkdir(parents=True, exist_ok=True)
    
    stdout_path = output_dir / f"{dataset_name}_hydra_run{run_number}_stdout.txt"
    
    timed_out = False
    
    try:
        # Check if this run was already completed
        if stdout_path.exists() and output_pods_path.exists():
            logger.info(f"Using cached Hydra output for {dataset_name} run {run_number}")
            output_text = stdout_path.read_text()
        else:
            # Build command: java -jar Hydra+.jar <baseline.csv> <output_pods.txt> <num_rows>
            cmd = [
                "java",
                *java_opts,
                "-jar",
                str(HYDRA_JAR_PATH),
                str(baseline_path),
                str(output_pods_path),
                str(1000000)
                # str(num_rows),

            ]
            
            logger.info(f"Running Hydra+ on {dataset_name} (run {run_number})...")
            logger.debug(f"Command: {' '.join(cmd)}")
            
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
                    f"Hydra+ run {run_number} timed out after {TIMEOUT_SECONDS} seconds"
                )
            
            # Save stdout to file
            stdout_path.write_text(output_text)
        
        # Parse metrics from output
        metrics = parse_hydra_output(output_text)
        if timed_out:
            metrics["error"] = f"Timeout after {TIMEOUT_SECONDS} seconds"
        metrics["stdout_file"] = str(stdout_path)
        metrics["pods_file"] = str(output_pods_path)
        
        return metrics
    
    except Exception as e:
        logger.error(f"Error on Hydra+ run {run_number}: {e}")
        return {
            "runtime_ms": None,
            "pod_count": None,
            "error": str(e)
        }


def run_incpod_once(
    baseline_path: Path,
    increment_path: Path,
    pods_path: Path,
    num_baseline_rows: int,
    num_increment_rows: int,
    dataset_name: str,
    run_number: int,
    output_dir: Path,
    temp_files: List[Path],
    java_opts: Optional[List[str]] = None,
) -> Dict[str, Any]:
    """
    Run IncPOD once and return metrics
    
    Args:
        baseline_path: Path to baseline CSV file
        increment_path: Path to increment CSV file
        pods_path: Path to file containing known PODs from Hydra+
        num_baseline_rows: Number of rows in baseline
        num_increment_rows: Number of rows in increment
        dataset_name: Name of the dataset (for identification)
        run_number: Run number (1-based)
        output_dir: Directory to store outputs
        temp_files: List to track temporary files for cleanup
        java_opts: Java options (default: DEFAULT_JAVA_OPTS)
    """
    java_opts = java_opts or DEFAULT_JAVA_OPTS
    output_dir.mkdir(parents=True, exist_ok=True)
    
    stdout_path = output_dir / f"{dataset_name}_incpod_run{run_number}_stdout.txt"
    
    timed_out = False
    
    try:
        # Check if this run was already completed
        if stdout_path.exists():
            logger.info(f"Using cached IncPOD output for {dataset_name} run {run_number}")
            output_text = stdout_path.read_text()
        else:
            # Build command: java -jar IncPOD.jar <baseline.csv> <increment.csv> <pods.txt> <num_baseline> <num_increment>
            cmd = [
                "java",
                *java_opts,
                "-jar",
                str(INCPOD_JAR_PATH),
                str(baseline_path),
                str(increment_path),
                str(pods_path),
                str(num_baseline_rows),
                str(num_increment_rows),
            ]
            
            logger.info(f"Running IncPOD on {dataset_name} (run {run_number})...")
            logger.debug(f"Command: {' '.join(cmd)}")
            
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
                    f"IncPOD run {run_number} timed out after {TIMEOUT_SECONDS} seconds"
                )
            
            # Save stdout to file
            stdout_path.write_text(output_text)
        
        # Parse metrics from output
        metrics = parse_incpod_output(output_text)
        if timed_out:
            metrics["error"] = f"Timeout after {TIMEOUT_SECONDS} seconds"
        metrics["stdout_file"] = str(stdout_path)
        
        return metrics
    
    except Exception as e:
        logger.error(f"Error on IncPOD run {run_number}: {e}")
        return {
            "runtime_ms": None,
            "incremental_runtime_ms": None,
            "pod_count": None,
            "error": str(e)
        }


def run_static_dataset(
    dataset_path: Path,
    dataset_name: str,
    separator: str,
    output_dir: Path,
) -> Dict[str, Any]:
    """
    Run Hydra+ and IncPOD on a static dataset
    Creates a baseline with one row and uses remaining data as increment
    (IncPOD doesn't work with empty baselines)
    """
    results = {
        "hydra_metrics": [],
        "incpod_metrics": []
    }
    temp_files = []
    
    try:
        # Count total rows in full dataset
        total_rows = count_csv_rows(dataset_path, separator)
        
        # Check if dataset is empty
        if total_rows == 0:
            logger.warning(f"Dataset {dataset_name} is empty, skipping")
            return results
        
        # IncPOD doesn't work with empty baselines, so we use first row as baseline
        # and remaining rows as increment
        baseline_path = create_baseline_with_one_row(dataset_path, separator)
        temp_files.append(baseline_path)
        
        increment_path = create_increment_without_first_row(dataset_path, separator)
        temp_files.append(increment_path)
        
        num_baseline_rows = 1
        num_increment_rows = total_rows - 1
        
        logger.info(f"\n{'='*60}")
        logger.info(f"Dataset: {dataset_name}")
        logger.info(f"Baseline rows: {num_baseline_rows}, Increment rows: {num_increment_rows}")
        logger.info(f"{'='*60}")
        
        # Run multiple times
        for run_num in range(1, RUNS_PER_CONFIG + 1):
            # Create PODs file path (temporary for each run)
            pods_path = Path(tempfile.mktemp(suffix='.txt'))
            temp_files.append(pods_path)
            
            # Step 1: Run Hydra+ on baseline
            logger.info(f"Step 1/2: Running Hydra+ (run {run_num}/{RUNS_PER_CONFIG})")
            hydra_metrics = run_hydra_once(
                baseline_path=baseline_path,
                output_pods_path=pods_path,
                num_rows=num_baseline_rows,
                dataset_name=dataset_name,
                run_number=run_num,
                output_dir=output_dir,
                temp_files=temp_files,
            )
            results["hydra_metrics"].append(hydra_metrics)
            
            # Step 2: Run IncPOD with baseline + increment
            logger.info(f"Step 2/2: Running IncPOD (run {run_num}/{RUNS_PER_CONFIG})")
            incpod_metrics = run_incpod_once(
                baseline_path=baseline_path,
                increment_path=increment_path,
                pods_path=pods_path,
                num_baseline_rows=num_baseline_rows,
                num_increment_rows=num_increment_rows,
                dataset_name=dataset_name,
                run_number=run_num,
                output_dir=output_dir,
                temp_files=temp_files,
            )
            results["incpod_metrics"].append(incpod_metrics)
    
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
    Run Hydra+ and IncPOD on all static datasets:
    - complete
    - complete_long
    - wikipedia static (final_state.csv files)
    """
    output_dir = Path("benchmark/incpod_results")
    output_dir.mkdir(parents=True, exist_ok=True)
    
    metrics_path = output_dir / "benchmark_metrics.json"
    if metrics_path.exists():
        all_metrics = json.loads(metrics_path.read_text())
    else:
        all_metrics = {}
    
    # 1. Process complete datasets
    logger.info("\n" + "="*80)
    logger.info("PROCESSING COMPLETE DATASETS")
    logger.info("="*80)
    
    complete_dir = Path("datasets/complete")
    if complete_dir.exists():
        for csv_file in sorted(complete_dir.glob("*.csv")):
            dataset_name = f"complete_{csv_file.stem}"
            
            if dataset_name in all_metrics:
                logger.info(f"Skipping {dataset_name} (already processed)")
                continue
            
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
    
    # 2. Process complete_long datasets
    logger.info("\n" + "="*80)
    logger.info("PROCESSING COMPLETE_LONG DATASETS")
    logger.info("="*80)
    
    complete_long_dir = Path("datasets/complete_long")
    if complete_long_dir.exists():
        for csv_file in sorted(complete_long_dir.glob("*.csv")):
            dataset_name = f"complete_long_{csv_file.stem}"
            
            if dataset_name in all_metrics:
                logger.info(f"Skipping {dataset_name} (already processed)")
                continue
            
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
    
    wikipedia_dir = Path("datasets/wikipedia/dynfd")
    if wikipedia_dir.exists():
        for folder in sorted(wikipedia_dir.iterdir()):
            if not folder.is_dir():
                continue
            
            final_state_file = folder / "final_state.csv"
            if not final_state_file.exists():
                continue
            
            dataset_name = f"wikipedia_static_{folder.name}"
            
            if dataset_name in all_metrics:
                logger.info(f"Skipping {dataset_name} (already processed)")
                continue
            
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
    
    logger.info("\n" + "="*80)
    logger.info("BENCHMARK COMPLETE!")
    logger.info(f"Results saved to: {metrics_path}")
    logger.info("="*80)
    
    return all_metrics


if __name__ == "__main__":
    run_all_experiments()
