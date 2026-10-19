"""Typed errors. Every error carries a stable code, a cause and a suggested fix
usable by humans and by AI (FR-AI-012). Try/Catch blocks match on `code`."""
from __future__ import annotations


class OoaError(Exception):
    code = "OOA.ERROR"
    retryable = False

    def __init__(self, message: str, *, code: str | None = None, suggested_fix: str | None = None,
                 retryable: bool | None = None, step_id: str | None = None, details: dict | None = None):
        super().__init__(message)
        self.message = message
        if code:
            self.code = code
        if retryable is not None:
            self.retryable = retryable
        self.suggested_fix = suggested_fix
        self.step_id = step_id
        self.details = details or {}

    def to_dict(self) -> dict:
        return {"code": self.code, "message": self.message, "suggestedFix": self.suggested_fix,
                "retryable": self.retryable, "stepId": self.step_id, "details": self.details}

    def __str__(self) -> str:
        fix = f" Fix: {self.suggested_fix}" if self.suggested_fix else ""
        return f"[{self.code}] {self.message}{fix}"


class ActionException(OoaError):
    """Raised by package actions. Packages choose their own codes, e.g. EXCEL.CELL_INVALID."""
    code = "ACTION.FAILED"


class FlowValidationError(OoaError):
    code = "FLOW.INVALID"

    def __init__(self, message: str, problems: list[dict] | None = None, **kw):
        super().__init__(message, details={"problems": problems or []}, **kw)
        self.problems = problems or []


class ExpressionError(OoaError):
    code = "EXPR.INVALID"


class VariableError(OoaError):
    code = "VAR.INVALID"


class StepTimeoutError(OoaError):
    code = "STEP.TIMEOUT"
    retryable = True


class RunCancelled(OoaError):
    code = "RUN.CANCELLED"


class SelectorNotFound(OoaError):
    code = "UI.SELECTOR_NOT_FOUND"
    retryable = True


class BusinessRuleException(OoaError):
    code = "BUSINESS.RULE"


class PackageError(OoaError):
    code = "PKG.ERROR"


class PolicyDenied(OoaError):
    code = "POLICY.DENIED"


class BudgetExceeded(OoaError):
    code = "AI.BUDGET_EXCEEDED"


class ThrownError(OoaError):
    """Raised by core/throw with a user-chosen code."""
    code = "USER.THROWN"
