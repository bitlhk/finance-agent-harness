"""Exercise the wrapper without recursively running Maven or accessing real services."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from run_onboarding import collect_controlled, controlled_environment, preflight, run_steps, steps


class OnboardingTest(unittest.TestCase):
    def test_live_environment_is_rejected_before_commands(self):
        for name in ("EMPLOYEE_AGENT_LIVE", "EMPLOYEE_AGENT_INSURANCE_LIVE", "NON_AI_BANKING_TRANSFER_LIVE"):
            with self.subTest(name=name):
                self.assertRaises(ValueError, controlled_environment, {name: "true"})
        env = controlled_environment({"PATH": "/bin", "MAVEN_ARGS": "-DskipTests", "PYTHONOPTIMIZE": "1"})
        self.assertEqual({"PATH": "/bin"}, env)

    def test_dirty_checkout_is_not_measured_as_clean(self):
        with patch("run_onboarding.git", return_value=" M README.md"):
            self.assertRaises(ValueError, preflight, Path("."), {})

    def test_fixed_plan_contains_all_existing_gates(self):
        names = [name for name, _ in steps("python")]
        self.assertEqual(["java-version", "maven-version", "contracts", "java-verify", "python-regressions",
                          "controlled-cli", "report-verification"], names)
        self.assertNotIn("-DskipTests", str(steps("python")))

    def exercise(self, codes):
        with tempfile.TemporaryDirectory() as directory, patch("run_onboarding.git", return_value="test-commit"):
            output = Path(directory)
            invocations = []

            def runner(command, root, env, log):
                invocations.append(command)
                log.write("controlled fixture only\n")
                return codes[len(invocations) - 1]

            result = run_steps(output, output, [("first", ["one"]), ("second", ["two"])], {}, runner)
            return result, json.loads((output / "report.json").read_text()), invocations

    def test_fail_fast_records_failure_and_does_not_call_later_gates(self):
        code, report, calls = self.exercise([4])
        self.assertEqual(1, code)
        self.assertEqual([["one"]], calls)
        self.assertEqual("FAIL", report["automatedStatus"])
        self.assertEqual(4, report["steps"][0]["exitCode"])

    def test_pass_does_not_claim_human_or_live_acceptance(self):
        code, report, calls = self.exercise([0, 0])
        self.assertEqual(0, code)
        self.assertEqual(2, len(calls))
        self.assertEqual("PASS", report["automatedStatus"])
        self.assertEqual("NOT_RUN", report["liveAuthorization"])
        self.assertEqual("NOT_RUN", report["realProviderSubstitution"])
        self.assertEqual("PENDING_REVIEW", report["independentHumanOnboarding"])
        self.assertIsNone(report["humanElapsedSeconds"])
        self.assertIsNone(report["helpRequests"])
        self.assertEqual(64, len(report["steps"][0]["logSha256"]))

    def test_empty_plan_cannot_pass(self):
        with patch("run_onboarding.git", return_value="test-commit"):
            self.assertRaises(ValueError, run_steps, Path("."), Path("."), [], {})

    def test_interrupted_gate_cannot_pass(self):
        with tempfile.TemporaryDirectory() as directory, patch("run_onboarding.git", return_value="test-commit"):
            output = Path(directory)
            with patch("run_onboarding.execute", side_effect=KeyboardInterrupt) as runner:
                code = run_steps(output, output, [("first", ["one"])], {}, runner)
            report = json.loads((output / "report.json").read_text())
            self.assertEqual(1, code)
            self.assertEqual("INTERRUPTED", report["automatedStatus"])

    def test_missing_artifacts_cannot_leave_a_pass_report(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            path = output / "report.json"
            path.write_text(json.dumps({"automatedStatus": "PASS"}))
            self.assertEqual(1, collect_controlled(output, output))
            self.assertEqual("FAIL", json.loads(path.read_text())["automatedStatus"])


if __name__ == "__main__":
    unittest.main()
