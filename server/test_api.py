"""Protocol tests use a paused queue; inference is exercised by smoke_test.py."""
import os
import tempfile
import unittest
from unittest.mock import patch

workspace = tempfile.TemporaryDirectory()
os.environ['SING_TOKEN'] = 'local-test-token-123'
os.environ['SING_DATA'] = workspace.name
import app as service
from fastapi.testclient import TestClient


class ApiTest(unittest.TestCase):
    def test_upload_auth_limits_results_and_cleanup(self):
        with TestClient(service.app) as client, patch.object(service.pool, 'submit'):
            headers = {'Authorization': 'Bearer local-test-token-123'}
            self.assertEqual(client.get('/health').status_code, 401)
            self.assertEqual(client.get('/health', headers=headers).json()['api_version'], 1)
            self.assertEqual(client.post('/jobs', headers=headers, content=b'').status_code, 400)
            with patch.object(service, 'MAX_BYTES', 2):
                self.assertEqual(client.post('/jobs', headers=headers, content=b'large').status_code, 413)
            ids = []
            for _ in range(4):
                response = client.post('/jobs', headers=headers, content=b'fixture')
                self.assertEqual(response.status_code, 202)
                ids.append(response.json()['id'])
            self.assertEqual(client.post('/jobs', headers=headers, content=b'x').status_code, 429)
            first = ids[0]
            self.assertEqual(client.get('/jobs/' + first, headers=headers).json()['status'], 'queued')
            self.assertEqual(client.get('/jobs/' + first + '/result', headers=headers).status_code, 409)
            # Simulated worker completion; real inference is a separate smoke test.
            service.jobs[first]['status'] = 'succeeded'
            (service.ROOT / first / 'instrumental.m4a').write_bytes(b'fixture-result')
            service.slots.release()
            self.assertEqual(client.get('/jobs/' + first + '/result', headers=headers).content, b'fixture-result')
            self.assertEqual(client.delete('/jobs/' + first, headers=headers).status_code, 200)
            self.assertEqual(client.get('/jobs/' + first, headers=headers).status_code, 404)
            for job_id in ids[1:]:
                client.delete('/jobs/' + job_id, headers=headers)
                service.separate(job_id)  # cancelled before inference: releases queue slot
            self.assertFalse(list(service.ROOT.iterdir()))


if __name__ == '__main__':
    unittest.main()
