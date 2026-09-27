from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any

from app.remediation.models import RemediationAction
from app.schemas import CamelModel


class RemediationPolicyErrorCode(StrEnum):
    UNKNOWN_REMEDIATION_PARAMETER = "UNKNOWN_REMEDIATION_PARAMETER"
    INVALID_REMEDIATION_PARAMETER_TYPE = "INVALID_REMEDIATION_PARAMETER_TYPE"
    INVALID_REMEDIATION_PARAMETER_VALUE = "INVALID_REMEDIATION_PARAMETER_VALUE"
    UNSUPPORTED_REMEDIATION_SCENARIO = "UNSUPPORTED_REMEDIATION_SCENARIO"


class RemediationValidationError(CamelModel):
    error_code: RemediationPolicyErrorCode
    message: str
    parameter: str | None = None


@dataclass(frozen=True)
class ParameterRule:
    name: str
    expected_type: type
    allowed_values: set[Any] | None = None
    min_value: int | float | None = None
    max_value: int | float | None = None

    def validate(self, value: Any) -> RemediationValidationError | None:
        if self.expected_type is bool:
            if type(value) is not bool:
                return RemediationValidationError(
                    error_code=RemediationPolicyErrorCode.INVALID_REMEDIATION_PARAMETER_TYPE,
                    parameter=self.name,
                    message=f"{self.name} must be a boolean",
                )
        elif self.expected_type is int:
            if type(value) is not int:
                return RemediationValidationError(
                    error_code=RemediationPolicyErrorCode.INVALID_REMEDIATION_PARAMETER_TYPE,
                    parameter=self.name,
                    message=f"{self.name} must be an integer",
                )
        elif not isinstance(value, self.expected_type):
            return RemediationValidationError(
                error_code=RemediationPolicyErrorCode.INVALID_REMEDIATION_PARAMETER_TYPE,
                parameter=self.name,
                message=f"{self.name} must be {self.expected_type.__name__}",
            )

        if self.allowed_values is not None and value not in self.allowed_values:
            return RemediationValidationError(
                error_code=RemediationPolicyErrorCode.INVALID_REMEDIATION_PARAMETER_VALUE,
                parameter=self.name,
                message=f"{self.name} value is not allowed",
            )
        if self.min_value is not None and value < self.min_value:
            return RemediationValidationError(
                error_code=RemediationPolicyErrorCode.INVALID_REMEDIATION_PARAMETER_VALUE,
                parameter=self.name,
                message=f"{self.name} must be >= {self.min_value}",
            )
        if self.max_value is not None and value > self.max_value:
            return RemediationValidationError(
                error_code=RemediationPolicyErrorCode.INVALID_REMEDIATION_PARAMETER_VALUE,
                parameter=self.name,
                message=f"{self.name} must be <= {self.max_value}",
            )
        return None


@dataclass(frozen=True)
class ScenarioRemediationPolicy:
    scenario_code: str
    parameters: dict[str, ParameterRule] = field(default_factory=dict)


_POLICIES: dict[str, ScenarioRemediationPolicy] = {
    "CACHE_BREAKDOWN": ScenarioRemediationPolicy(
        scenario_code="CACHE_BREAKDOWN",
        parameters={
            "enableMutex": ParameterRule("enableMutex", bool, allowed_values={True}),
            "enableLogicalExpire": ParameterRule("enableLogicalExpire", bool, allowed_values={True}),
        },
    ),
    "DB_CONNECTION_POOL_EXHAUSTION": ScenarioRemediationPolicy(
        scenario_code="DB_CONNECTION_POOL_EXHAUSTION",
        parameters={
            "enableFastRelease": ParameterRule("enableFastRelease", bool, allowed_values={True}),
        },
    ),
    "DOWNSTREAM_TIMEOUT": ScenarioRemediationPolicy(
        scenario_code="DOWNSTREAM_TIMEOUT",
        parameters={
            "enableFallback": ParameterRule("enableFallback", bool, allowed_values={True}),
        },
    ),
    "RETRY_STORM": ScenarioRemediationPolicy(
        scenario_code="RETRY_STORM",
        parameters={
            "enableRetryLimit": ParameterRule("enableRetryLimit", bool, allowed_values={True}),
            "enableJitter": ParameterRule("enableJitter", bool, allowed_values={True}),
            "retryBackoffMs": ParameterRule("retryBackoffMs", int, min_value=1, max_value=5000),
        },
    ),
}

_FORBIDDEN_PATCH_KEYS = {"scenarioCode", "scenario_code", "experimentId", "experiment_id"}


def get_policy(scenario_code: str | None) -> ScenarioRemediationPolicy | None:
    return _POLICIES.get((scenario_code or "").upper())


def validate_patch(
    scenario_code: str | None,
    parameter_patch: dict[str, Any],
) -> list[RemediationValidationError]:
    policy = get_policy(scenario_code)
    if not policy:
        return [
            RemediationValidationError(
                error_code=RemediationPolicyErrorCode.UNSUPPORTED_REMEDIATION_SCENARIO,
                message=f"Remediation planning is unsupported for scenario {scenario_code or 'UNKNOWN'}",
            )
        ]

    errors: list[RemediationValidationError] = []
    for parameter, value in parameter_patch.items():
        if parameter in _FORBIDDEN_PATCH_KEYS or parameter not in policy.parameters:
            errors.append(
                RemediationValidationError(
                    error_code=RemediationPolicyErrorCode.UNKNOWN_REMEDIATION_PARAMETER,
                    parameter=parameter,
                    message=f"{parameter} is not allowed in remediation parameterPatch",
                )
            )
            continue
        error = policy.parameters[parameter].validate(value)
        if error:
            errors.append(error)
    return errors


def validate_action(
    scenario_code: str | None,
    action: RemediationAction,
) -> list[RemediationValidationError]:
    return validate_patch(scenario_code, action.parameter_patch)
