"""Qualification input validation only; actual native checks run separately."""
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest

from engineering.verify_curated_native import verify


class QualificationSubjectTest(unittest.TestCase):
    def test_invalid_subject_is_rejected_before_any_output_or_process(self):
        for subject in ('', '../customer', 'customer demo', None, 'a' * 121):
            with self.subTest(subject=subject), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / 'never-created'
                with self.assertRaisesRegex(ValueError, 'SYNTHETIC_TRUSTED_SUBJECT_INVALID'):
                    verify(SimpleNamespace(trusted_subject=subject, output=output))
                self.assertFalse(output.exists())


if __name__ == '__main__':
    unittest.main()
