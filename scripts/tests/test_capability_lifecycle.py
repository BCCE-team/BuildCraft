from pathlib import Path
import tempfile
import unittest

from capability_lifecycle_fixture import run


class CapabilityLifecycle(unittest.TestCase):
    def test_real_forge_helper_invalidates_and_revives_cached_storage_handles(self):
        with tempfile.TemporaryDirectory(prefix='bc-capability-lifecycle-') as tmp:
            print(run(Path(tmp)/'probe'), flush=True)


if __name__ == '__main__':
    unittest.main(verbosity=2)
