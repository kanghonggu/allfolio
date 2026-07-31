package com.allfolio.reconciliation.domain

enum class RunType { VALIDATION, RECONCILIATION, ALL }
enum class RunStatus { RUNNING, COMPLETED, FAILED }
enum class ReconTrigger { MANUAL, SCHEDULED }
enum class SummaryStatus { PASSED, DIFF_FOUND, FAILED }
enum class DiffType { VALUE_MISMATCH, MISSING_INTERNAL, MISSING_EXTERNAL, RULE_VIOLATION }
enum class KdValueType { ABS, RATIO }
