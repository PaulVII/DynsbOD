"""Run all experiment modules sequentially."""

import importlib
import pkgutil
import runpy
import sys


def discover_modules(package_name):
    """Return fully-qualified module names in a package."""
    package = importlib.import_module(package_name)
    module_names = []
    for module_info in pkgutil.iter_modules(package.__path__):
        if module_info.ispkg:
            continue
        name = module_info.name
        if name.startswith("_") or name == "__main__":
            continue
        module_names.append(f"{package_name}.{name}")
    return sorted(module_names)


def main():
    """Run all experiments sequentially."""
    experiments = discover_modules(__package__)
    print("=" * 80)
    print("Running all experiments sequentially")
    print("=" * 80)

    for i, module_name in enumerate(experiments, 1):
        print(f"\n[{i}/{len(experiments)}] Running {module_name}...")
        print("-" * 80)

        try:
            # Execute module as if it were run with -m
            runpy.run_module(module_name, run_name="__main__")
            print(f"✓ Completed {module_name}")
        except Exception as e:
            print(f"✗ Error in {module_name}: {e}", file=sys.stderr)
            sys.exit(1)
    
    print("\n" + "=" * 80)
    print("All experiments completed successfully!")
    print("=" * 80)


if __name__ == "__main__":
    main()
