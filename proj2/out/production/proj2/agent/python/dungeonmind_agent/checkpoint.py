"""Centralized LangGraph checkpoint lifecycle."""

from __future__ import annotations

import sqlite3
from pathlib import Path

from langgraph.checkpoint.memory import InMemorySaver
from langgraph.checkpoint.sqlite import SqliteSaver


class CheckpointManager:
    def __init__(self, path: str | None = None) -> None:
        self._connection: sqlite3.Connection | None = None
        if path is None:
            self.saver = InMemorySaver()
        else:
            target = Path(path)
            target.parent.mkdir(parents=True, exist_ok=True)
            self._connection = sqlite3.connect(
                target, check_same_thread=False
            )
            self.saver = SqliteSaver(self._connection)
            self.saver.setup()

    def close(self) -> None:
        if self._connection is not None:
            self._connection.close()
            self._connection = None
