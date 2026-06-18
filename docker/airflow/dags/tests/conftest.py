"""Pytest fixtures for per_phase_tasks unit tests."""
import sys
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

# Ensure dags/ is importable
DAGS_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(DAGS_DIR))


@pytest.fixture
def mock_dag_run_conf():
    """Factory: mock context['dag_run'].conf for parse_scope."""

    def _make(conf):
        dag_run = MagicMock()
        dag_run.conf = conf
        return {"dag_run": dag_run}

    return _make


@pytest.fixture
def mock_external_api_conn():
    """Mock the Airflow 'external_api' connection to localhost."""
    with patch("per_phase_tasks.BaseHook") as base_hook:
        conn = MagicMock()
        conn.host = "localhost"
        conn.port = 8081
        base_hook.get_connection.return_value = conn
        yield base_hook