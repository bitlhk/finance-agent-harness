"""Install only admission metadata into controlled source fixtures, not fake grants."""
from pathlib import Path
import shutil


def copy_admission_metadata(root):
    fixture = Path(__file__).parent / "fixtures/component-assembly"
    for source in fixture.rglob("*"):
        if source.is_file() and (source.name == "assembly.v0.1.json" or source.name.endswith(".schema.json")):
            destination = root / source.relative_to(fixture)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
