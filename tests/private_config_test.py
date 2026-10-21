"""Private build guard regression tests; use synthetic values only."""
from pathlib import Path
import json
import os
import subprocess
import tempfile

script = str(Path('scripts/private_config.py').resolve())
clean = {k: v for k, v in os.environ.items() if k not in
         ('APPKEY', 'SECRETKEY', 'PRIVATE_BUILD', 'PRIVATE_REPO_CONFIRMED', 'CI')}
with tempfile.TemporaryDirectory() as directory:
    cases = [
        ({'PRIVATE_BUILD': '1', 'CI': 'true', 'APPKEY': 'dummy', 'SECRETKEY': 'dummy'}, False),
        ({'PRIVATE_BUILD': '1', 'PRIVATE_REPO_CONFIRMED': '1', 'CI': 'true'}, False),
        ({'PRIVATE_BUILD': '1', 'PRIVATE_REPO_CONFIRMED': '1', 'CI': 'true',
          'APPKEY': 'dummy', 'SECRETKEY': 'dummy'}, True),
    ]
    for env, expected in cases:
        result = subprocess.run(['python', script], cwd=directory,
                                env={**clean, **env}, capture_output=True)
        assert (result.returncode == 0) == expected
        assert b'dummy' not in result.stdout + result.stderr
    resource = Path(directory) / 'build/res/raw/baidu_config.json'
    assert json.loads(resource.read_text()) == {'appkey': 'dummy', 'secretkey': 'dummy'}
    subprocess.run(['python', script], cwd=directory, env=clean, check=True)
    assert not resource.exists()
print('Private-build guard, missing credentials, safe logs and public-mode cleanup passed')
