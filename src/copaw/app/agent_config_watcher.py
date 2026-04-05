# -*- coding: utf-8 -*-
"""Watch agent.json for changes and auto-reload agent components.

This watcher monitors an agent's workspace/agent.json file for changes
and automatically reloads configurations without requiring manual restart.
"""

from __future__ import annotations

import asyncio
import logging
from pathlib import Path
from typing import Optional

from ..config.config import load_agent_config

logger = logging.getLogger(__name__)

# How often to poll (seconds)
DEFAULT_POLL_INTERVAL = 2.0


class AgentConfigWatcher:
    """Poll agent.json mtime and reload changed configs automatically.

    This watcher is agent-scoped and monitors a specific agent's
    workspace/agent.json file for configuration changes.
    """

    def __init__(
        self,
        agent_id: str,
        workspace_dir: Path,
        poll_interval: float = DEFAULT_POLL_INTERVAL,
        **kwargs,
    ):
        """Initialize agent config watcher.

        Args:
            agent_id: Agent ID to monitor
            workspace_dir: Path to agent's workspace directory
            poll_interval: How often to check for changes (seconds)
        """
        self._agent_id = agent_id
        self._workspace_dir = workspace_dir
        self._config_path = workspace_dir / "agent.json"
        self._poll_interval = poll_interval
        self._task: Optional[asyncio.Task] = None

        # mtime of agent.json at last check
        self._last_mtime: float = 0.0

    async def start(self) -> None:
        """Take initial snapshot and start the polling task."""
        self._snapshot()
        self._task = asyncio.create_task(
            self._poll_loop(),
            name=f"agent_config_watcher_{self._agent_id}",
        )
        logger.info(
            f"AgentConfigWatcher started for agent {self._agent_id} "
            f"(poll={self._poll_interval}s, path={self._config_path})",
        )

    async def stop(self) -> None:
        """Stop the polling task."""
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
            self._task = None
        logger.info(f"AgentConfigWatcher stopped for agent {self._agent_id}")

    # ------------------------------------------------------------------
    # Internal methods
    # ------------------------------------------------------------------

    def _snapshot(self) -> None:
        """Record mtime of agent.json for change detection."""
        try:
            self._last_mtime = self._config_path.stat().st_mtime
        except FileNotFoundError:
            self._last_mtime = 0.0

    async def _poll_loop(self) -> None:
        """Main polling loop."""
        while True:
            try:
                await asyncio.sleep(self._poll_interval)
                await self._check()
            except Exception:
                logger.exception(
                    f"AgentConfigWatcher ({self._agent_id}): "
                    f"poll iteration failed",
                )

    async def _check(self) -> None:
        """Check for config changes and reload if needed."""
        try:
            mtime = self._config_path.stat().st_mtime
        except FileNotFoundError:
            return

        if mtime == self._last_mtime:
            return

        self._last_mtime = mtime

        try:
            load_agent_config(self._agent_id)
        except Exception:
            logger.exception(
                f"AgentConfigWatcher ({self._agent_id}): "
                f"failed to parse agent.json",
            )
            return

        logger.info(
            f"AgentConfigWatcher ({self._agent_id}): "
            f"agent.json changed, config reloaded",
        )
