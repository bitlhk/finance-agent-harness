"""Exercise real fixed Git transport and the non-secret Maven property handoff."""
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

import yaml

from prepare_original_market_risk_tool import export_properties, fixed_checkout


class OriginalToolCiTest(unittest.TestCase):
    def test_ci_prepares_tool_before_maven_and_supplies_both_properties(self):
        root = Path(__file__).resolve().parents[1]
        workflow = yaml.safe_load((root / '.github/workflows/ci.yml').read_text(encoding='utf-8'))
        job = workflow['jobs']['offline-contracts-and-java']
        self.assertEqual(job['strategy']['matrix']['python'], ['3.10', '3.12'])
        steps = job['steps']
        prepare = next(i for i, step in enumerate(steps)
                       if step.get('name') == 'Prepare pinned original market-risk tool')
        verify_steps = [i for i, step in enumerate(steps)
                        if (tokens := shlex.split(step.get('run', '')))[:1] == ['mvn']
                        and 'verify' in tokens]
        self.assertEqual(len(verify_steps), 1, 'Expected one Maven verify step')
        verify = verify_steps[0]
        self.assertLess(prepare, verify)
        self.assertIn('--github-env "$GITHUB_ENV"', steps[prepare]['run'])
        for prop in ['fh.originalToolRelease', 'fh.originalToolReleaseDigest']:
            self.assertIn('-D' + prop + '=', steps[verify]['run'])
        self.assertNotIn('continue-on-error', steps[prepare])
        assets = next(step for step in steps if step.get('name') == 'Original market-risk asset tests')
        self.assertIn('python -m pytest engineering/test_market_risk_capital_assets.py', assets['run'])
        tooling = next(step for step in steps if step.get('name') == 'Install original-tool test tooling')
        self.assertIn('pytest==9.2.0', tooling['run'])

    def test_fixed_checkout_keeps_requested_revision_not_latest_head(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source"
            source.mkdir()
            git = ["git", "-c", "core.autocrlf=false", "-C", str(source)]
            subprocess.run([*git, "init", "-q"], check=True)
            (source / "assets").mkdir()
            file = source / "assets/value.txt"
            file.write_text("frozen\n")
            subprocess.run([*git, "add", "."], check=True)
            commit = [*git, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm"]
            subprocess.run([*commit, "frozen"], check=True)
            revision = subprocess.check_output([*git, "rev-parse", "HEAD"]).decode().strip()
            file.write_text("new head\n")
            subprocess.run([*git, "add", "."], check=True)
            subprocess.run([*commit, "later"], check=True)
            selected = fixed_checkout(source.as_uri(), revision, root / "checkout", ["assets"])
            self.assertEqual((selected / "assets/value.txt").read_text(), "frozen\n")
            with self.assertRaises(FileExistsError):
                fixed_checkout(source.as_uri(), revision, selected, ["assets"])
            with self.assertRaises(subprocess.CalledProcessError):
                fixed_checkout(source.as_uri(), "0" * 40, root / "invalid", ["assets"])

    def test_export_preserves_digest_and_rejects_environment_injection(self):
        with tempfile.TemporaryDirectory() as directory:
            env = Path(directory) / "env"
            release = Path(directory) / "release with spaces"
            identity = "sha256:" + "a" * 64
            export_properties(release, identity, env)
            self.assertEqual(env.read_text().splitlines(), [
                "FH_ORIGINAL_TOOL_RELEASE=" + str(release.resolve()),
                "FH_ORIGINAL_TOOL_RELEASE_DIGEST=" + identity])
            before = env.read_bytes()
            with self.assertRaises(ValueError):
                export_properties(release, identity + "\nINJECTED=1", env)
            self.assertEqual(env.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
