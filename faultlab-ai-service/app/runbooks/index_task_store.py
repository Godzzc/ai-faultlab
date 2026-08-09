import json
import logging
from pathlib import Path

from app.runbooks.index_task_models import RunbookIndexTask

logger = logging.getLogger(__name__)

MAX_TASKS = 100


def default_task_store_path() -> Path:
    return Path(__file__).resolve().parents[2] / "data" / "runbook_index_tasks.json"


class RunbookIndexTaskStore:
    def __init__(self, task_path: Path | str | None = None) -> None:
        self.task_path = Path(task_path) if task_path else default_task_store_path()

    def load_tasks(self) -> list[RunbookIndexTask]:
        if not self.task_path.exists():
            return []
        try:
            raw = json.loads(self.task_path.read_text(encoding="utf-8"))
            if not isinstance(raw, list):
                logger.warning("Runbook index task store root is not a list")
                return []
            return [RunbookIndexTask.model_validate(item) for item in raw if isinstance(item, dict)]
        except Exception as exc:
            logger.warning("Failed to load runbook index tasks: %s", exc)
            return []

    def save_tasks(self, tasks: list[RunbookIndexTask]) -> None:
        retained = tasks[-MAX_TASKS:]
        self.task_path.parent.mkdir(parents=True, exist_ok=True)
        self.task_path.write_text(
            json.dumps(
                [task.model_dump(by_alias=True) for task in retained],
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )

    def add_task(self, task: RunbookIndexTask) -> RunbookIndexTask:
        tasks = self.load_tasks()
        tasks.append(task)
        self.save_tasks(tasks)
        return task

    def update_task(self, task: RunbookIndexTask) -> RunbookIndexTask:
        tasks = self.load_tasks()
        updated = False
        for index, existing in enumerate(tasks):
            if existing.task_id == task.task_id:
                tasks[index] = task
                updated = True
                break
        if not updated:
            tasks.append(task)
        self.save_tasks(tasks)
        return task

    def get_task(self, task_id: str) -> RunbookIndexTask | None:
        for task in self.load_tasks():
            if task.task_id == task_id:
                return task
        return None

    def list_tasks(self, limit: int = 20) -> list[RunbookIndexTask]:
        limit = max(1, limit or 20)
        return list(reversed(self.load_tasks()))[:limit]
