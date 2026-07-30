import json
import logging
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)


def default_state_path() -> Path:
    return Path(__file__).resolve().parents[2] / "data" / "runbook_index_state.json"


class IndexStateStore:
    def __init__(self, state_path: Path | str | None = None) -> None:
        self.state_path = Path(state_path) if state_path else default_state_path()

    def load_state(self) -> dict[str, Any]:
        if not self.state_path.exists():
            return {"documents": {}}
        try:
            with self.state_path.open("r", encoding="utf-8") as file:
                state = json.load(file)
            if not isinstance(state, dict):
                logger.warning("Runbook index state root is not an object")
                return {"documents": {}}
            documents = state.get("documents")
            if not isinstance(documents, dict):
                state["documents"] = {}
            return state
        except Exception as exc:
            logger.warning("Failed to load runbook index state: %s", exc)
            return {"documents": {}}

    def save_state(self, state: dict[str, Any]) -> None:
        try:
            self.state_path.parent.mkdir(parents=True, exist_ok=True)
            with self.state_path.open("w", encoding="utf-8") as file:
                json.dump(state, file, ensure_ascii=False, indent=2)
        except Exception as exc:
            logger.warning("Failed to save runbook index state: %s", exc)

    def get_document_state(self, doc_id: str) -> dict[str, Any] | None:
        return self.load_state().get("documents", {}).get(doc_id)

    def update_document_state(
        self,
        doc_id: str,
        document_state: dict[str, Any],
    ) -> dict[str, Any]:
        state = self.load_state()
        state.setdefault("documents", {})[doc_id] = document_state
        self.save_state(state)
        return state

    def remove_document_state(self, doc_id: str) -> dict[str, Any]:
        state = self.load_state()
        state.setdefault("documents", {}).pop(doc_id, None)
        self.save_state(state)
        return state
