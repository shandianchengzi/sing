#!/usr/bin/env python3
"""Inject personal Baidu credentials into build-only resources; never print values."""
import json
import os
import sys
from pathlib import Path

out = Path('build/res/raw/baidu_config.json')
if '--clean' in sys.argv:
    out.unlink(missing_ok=True)
    raise SystemExit(0)
if os.environ.get('PRIVATE_BUILD') == '1':
    if os.environ.get('CI') and os.environ.get('PRIVATE_REPO_CONFIRMED') != '1':
        raise SystemExit('Private build requires a verified private repository')
    values = {key: os.environ.get(key.upper(), '').strip() for key in ('appkey', 'secretkey')}
    if not all(values.values()):
        raise SystemExit('Configure APPKEY and SECRETKEY in repository Actions Secrets')
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(values), encoding='utf-8')
    out.chmod(0o600)
    print('Personal Baidu configuration injected into private build resources')

else:
    out.unlink(missing_ok=True)
