# jiuwen_adapter package

# Monkey-patches to fix two issues in JiuwenSwarm/openjiuwen:
# 1. RailManager._registered_rails not cleared when agent instance changes
# 2. DeepAgent.register_rail silently drops BRIDGE_EVENT callbacks when _react_agent is None
# 3. _register_rail_selective: log whether _react_agent is set when registering callbacks
#
# These patches are applied at extension import time and persist for the process lifetime.

import logging
import os as _os
import time as _time

_patch_logger = logging.getLogger("guardrail_jiuwen.patch")

_DEBUG_LOG = _os.path.join(_os.path.dirname(__file__), "_patch_debug.log")

def _dlog(msg: str) -> None:
    try:
        with open(_DEBUG_LOG, "a", encoding="utf-8") as _f:
            _f.write(f"[{_time.strftime('%Y-%m-%d %H:%M:%S')}] {msg}\n")
    except Exception:
        pass

# Write to a debug file to confirm __init__.py is executed
try:
    with open(_DEBUG_LOG, "w") as _f:
        _f.write("__init__.py executed\n")
except Exception:
    pass

# ── Patch 1: Clear _registered_rails on agent instance change ──
try:
    from jiuwenswarm.agents.harness.common.plugins.rail_manager import get_rail_manager as _get_rm

    _rm = _get_rm()
    if not getattr(_rm, "_guardrail_patched_rm", False):
        _original_set_agent = _rm.set_agent_instance

        def _patched_set_agent(agent_instance):
            if _rm._agent_instance is not agent_instance:
                _rm._registered_rails.clear()
            _original_set_agent(agent_instance)

        _rm.set_agent_instance = _patched_set_agent
        _rm._guardrail_patched_rm = True

    _dlog(f"Patch1 done: rm_patched={getattr(_rm, '_guardrail_patched_rm', False)}")
except Exception as _e:
    _dlog(f"Patch1 failed: {_e}")

# ── Patch 2: Queue rail to _pending_rails when _react_agent is not yet initialized ──
try:
    from openjiuwen.harness.deep_agent import DeepAgent as _DeepAgent

    if not getattr(_DeepAgent, "_guardrail_patched_da_v2", False):
        _original_register_rail = _DeepAgent.register_rail

        async def _patched_register_rail(self, rail):
            _dlog(f"register_rail called: rail={type(rail).__name__}, _react_agent={self._react_agent}, _initialized={self._initialized}")
            if self._react_agent is None:
                _dlog(f"register_rail: _react_agent is None, queuing to _pending_rails")
                self._pending_rails.append(rail)
                rail.init(self)
                return self
            _dlog(f"register_rail: _react_agent is set, calling original register_rail")
            return await _original_register_rail(self, rail)

        _DeepAgent.register_rail = _patched_register_rail
        _DeepAgent._guardrail_patched_da_v2 = True

    _dlog(f"Patch2 done: da_patched={getattr(_DeepAgent, '_guardrail_patched_da_v2', False)}")
except Exception as _e:
    _dlog(f"Patch2 failed: {_e}")

# ── Patch 3: Log _register_rail_selective to trace callback registration ──
try:
    from openjiuwen.harness.deep_agent import DeepAgent as _DeepAgent2

    if not getattr(_DeepAgent2, "_guardrail_patched_rrs", False):
        _original_rrs = _DeepAgent2._register_rail_selective

        async def _patched_rrs(self, rail):
            _dlog(f"_register_rail_selective: rail={type(rail).__name__}, _react_agent is {'NOT None' if self._react_agent is not None else 'None'}")
            callbacks = rail.get_callbacks()
            _dlog(f"_register_rail_selective: callbacks={[str(k) for k in callbacks.keys()]}")
            if self._react_agent is None:
                _dlog(f"_register_rail_selective: WARNING _react_agent is None! BRIDGE_EVENT callbacks will be SILENTLY DROPPED!")
            result = await _original_rrs(self, rail)
            _dlog(f"_register_rail_selective: done, _registered_rails count={len(self._registered_rails)}")
            return result

        _DeepAgent2._register_rail_selective = _patched_rrs
        _DeepAgent2._guardrail_patched_rrs = True

    _dlog(f"Patch3 done: rrs_patched={getattr(_DeepAgent2, '_guardrail_patched_rrs', False)}")
except Exception as _e:
    _dlog(f"Patch3 failed: {_e}")

# ── Patch 4: Log _ensure_initialized and unregister_rail to trace rail lifecycle ──
try:
    from openjiuwen.harness.deep_agent import DeepAgent as _DeepAgent3

    if not getattr(_DeepAgent3, "_guardrail_patched_init", False):
        _original_ensure_init = _DeepAgent3._ensure_initialized

        async def _patched_ensure_init(self):
            _dlog(f"_ensure_initialized: called, _initialized={self._initialized}, "
                  f"stale={len(self._stale_rails)}, pending={len(self._pending_rails)}, "
                  f"registered={len(self._registered_rails)}")
            for r in self._stale_rails:
                _dlog(f"  stale: {type(r).__name__}")
            for r in self._pending_rails:
                _dlog(f"  pending: {type(r).__name__}")
            for r in self._registered_rails:
                _dlog(f"  registered: {type(r).__name__}")
            await _original_ensure_init(self)
            _dlog(f"_ensure_initialized: done, _initialized={self._initialized}, "
                  f"registered={len(self._registered_rails)}")

        _DeepAgent3._ensure_initialized = _patched_ensure_init
        _DeepAgent3._guardrail_patched_init = True

    if not getattr(_DeepAgent3, "_guardrail_patched_unreg", False):
        _original_unreg = _DeepAgent3.unregister_rail

        async def _patched_unreg(self, rail):
            _dlog(f"unregister_rail: rail={type(rail).__name__}")
            return await _original_unreg(self, rail)

        _DeepAgent3.unregister_rail = _patched_unreg
        _DeepAgent3._guardrail_patched_unreg = True

    _dlog(f"Patch4 done: init_patched={getattr(_DeepAgent3, '_guardrail_patched_init', False)}, "
          f"unreg_patched={getattr(_DeepAgent3, '_guardrail_patched_unreg', False)}")
except Exception as _e:
    _dlog(f"Patch4 failed: {_e}")

# ── Patch 5: Log AgentCallbackContext.fire to see if BEFORE_MODEL_CALL is triggered ──
try:
    from openjiuwen.core.single_agent.rail.base import AgentCallbackContext as _Ctx
    from openjiuwen.core.runner import Runner as _Runner

    if not getattr(_Ctx, "_guardrail_patched_fire", False):
        _original_fire = _Ctx.fire

        async def _patched_fire(self, event):
            _dlog(f"ctx.fire called: event={event}, agent={type(self.agent).__name__}")
            if "before_model_call" in str(event).lower() or "after_model_call" in str(event).lower():
                agent_event = f"{self.agent.agent_callback_manager.event_namespace}_{event}"
                cbs = _Runner.callback_framework._callbacks.get(agent_event, [])
                _dlog(f"  framework event={agent_event}, callbacks_count={len(cbs)}")
                for ci in cbs:
                    _dlog(f"    callback: {ci.callback}, enabled={ci.enabled}")
            await _original_fire(self, event)

        _Ctx.fire = _patched_fire
        _Ctx._guardrail_patched_fire = True

    _dlog(f"Patch5 done: fire_patched={getattr(_Ctx, '_guardrail_patched_fire', False)}")
except Exception as _e:
    _dlog(f"Patch5 failed: {_e}")
