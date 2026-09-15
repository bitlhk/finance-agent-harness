# Upstream intent implementation feedback

Reviewed upstream mobile-banking main `7988c4af399e4506705b1363723339d9f80fca67`.
No upstream source was imported or modified in this change.

Items to report to its owner before any future reuse:

1. `ModelBackedIntentRecognizer` falls back to the first recall hit when model
   arbitration raises a runtime exception. TASK and ambiguous inputs should become
   unresolved or clarification, not an executable top-one result.
2. `DeepAgentTaskPlanner` similarly creates tasks from candidate IDs when planning
   returns no tasks. Execution eligibility and operation risk must gate this fallback.
3. The latest isolated intent-engine run had 133 tests, 4 failures and 3 skips;
   `转账前要核对什么` ranked insurance above transfer. That phrase is consultation:
   evaluation must distinguish domain relevance from invoking a transfer TASK.
4. Context maps are not an identity boundary. A Host must project trusted current
   subject, session generation and task state, and invalidate them on subject change
   or revocation.

Reusable ideas remain capability utterances and negative boundaries, original plus
normalized terminology, hybrid recall evidence, and goal decomposition. The full
DeepAgent planner, OpenSearch/Nacos infrastructure and top-one failure behavior are
outside Finance Harness V0.1.
