from pathlib import Path
import subprocess
import json
import time
import tempfile
from tqdm import tqdm
from datetime import datetime, timedelta
import logging
from benchmark.dynsbod_runner import FILE_PRESETS
logging.basicConfig(format='%(asctime)s %(levelname)s:%(message)s', level=logging.INFO)


complete_easy_configs = FILE_PRESETS["complete"]()
complete_slow_configs = FILE_PRESETS["complete"](suffix='_long')

wikipedia_static_configs = FILE_PRESETS["wikipedia_baseline"]()

# Combine both presets
all_file_configs = complete_easy_configs + complete_slow_configs + wikipedia_static_configs

files_to_run_on = [(config["file_identifier"],config["data"]["increments"][0]) for config in all_file_configs]


# skip actor as it ran OOM 3 times with Xmx 28GB after 3+ hours of trying

def run_hyod_benchmark(file: str, identifier: str, run_number: int, output_dir: Path):
    """Run HyOD once and return metrics and stdout"""
    # Only save stdout for the first run, use temp directory for others
    stdout_path = output_dir / f'{identifier}_run{run_number}_stdout.txt'
    try:
        if stdout_path.exists():
            logging.info(f"Skip running {file}, as file {stdout_path} already exists")
            output_lines = stdout_path.read_text().splitlines()
        else:
            if run_number == 1:
                result_path = output_dir / f'{identifier}_run{run_number}_hyod_results.txt'
            else:
                temp_dir = tempfile.mkdtemp()
                result_path = Path(temp_dir) / f'{identifier}_run{run_number}_hyod_results.txt'

            sep = ';' if 'horse' in file or 'plista' in file or 'adult' in file else ','
            
            # Run with 28GB RAM, 12 hour timeout
            command = f"""java -Xms2g -Xmx28g -jar datasets/HyOD_modified.jar '{file}' '{result_path.absolute()}' {sep}"""
            timeout_seconds = 12 * 60 * 60  # 12 hours
            
            logging.info(f"Running HyOD on {identifier} (run {run_number}/10)...")
            process = subprocess.Popen(command, shell=True, text=True, 
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            output_lines = []
            try:
                for line in process.stdout:
                    print(line, end='')
                    output_lines.append(line)
                process.wait(timeout=timeout_seconds)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
                logging.warning(f"Run {run_number} timed out after 12 hours")
                return {
                    "MemoryCost": None,
                    "TotalTime": None,
                    "Error": "Timeout after 12 hours"
                }
            
            stdout_text = ''.join(output_lines)
            
            # Save stdout to file
            stdout_path.write_text(stdout_text)
            time.sleep(5)

            # Clean up temp directory if used
            if run_number > 1:
                import shutil
                shutil.rmtree(Path(result_path).parent)
        # Parse metrics from stdout
        mem_used = None
        total_time = None
        for line in output_lines:
            if "MemoryCost: " in line:
                mem_used = line.split("MemoryCost: ")[-1].strip()
            if "TotalTime:" in line:
                total_time = line.split("TotalTime:")[-1].strip()
        
        result = {
            "MemoryCost": mem_used,
            "TotalTime": total_time,
            "stdout_file": str(stdout_path)
        }
        
        return result
    
    except Exception as e:
        logging.error(f"Error on run {run_number}: {e}")
        # Clean up temp directory if it exists
        if run_number > 1:
            try:
                import shutil
                shutil.rmtree(Path(result_path).parent)
            except:
                pass
        return {
            "MemoryCost": None,
            "TotalTime": None,
            "Error": str(e)
        }
        


def benchmark_all_files():
    """Run benchmarks on all files 10 times each"""
    output_dir = Path("benchmark/static_algorithm/results")
    output_dir.mkdir(parents=True, exist_ok=True)

    num_runs = 1

    metrics_path = output_dir / "benchmark_metrics.json"
    if metrics_path.exists():
        all_metrics = json.loads(metrics_path.read_text())
    else:
        all_metrics = {}
    
    for (identifier, file) in tqdm(files_to_run_on, desc="Processing files"):
        file_metrics = []
        start = datetime.now()
        
        for run_num in range(1, num_runs + 1):
            metrics = run_hyod_benchmark(file, identifier, run_num, output_dir)
            file_metrics.append(metrics)
            
            if datetime.now() - start > timedelta(hours=8):
                logging.warning("Overall runtime limit exceeded")
                break
        
        all_metrics[identifier] = file_metrics
        
        # Save metrics after each file completes
        metrics_path.write_text(json.dumps(all_metrics, indent=4))
    
    logging.info(f"\nBenchmark complete! Results saved to {output_dir}")
    return all_metrics


if __name__ == "__main__":
    benchmark_all_files()

