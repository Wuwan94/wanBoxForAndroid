const CLI_PATH = '/data/adb/modules/wanbox/bin/wanboxctl';

const COMMANDS = Object.freeze({
  status: 'status',
  dashboard: 'dashboard',
  logs: 'logs',
  start: 'start',
  stop: 'stop',
  restart: 'restart',
});

export async function runAction(action, execute) {
  if (typeof action !== 'string' || !Object.hasOwn(COMMANDS, action)) {
    throw new Error('action_not_allowed');
  }
  if (typeof execute !== 'function') {
    throw new Error('module_bridge_unavailable');
  }

  const result = await execute(`${CLI_PATH} ${COMMANDS[action]}`);
  if (!result || result.errno !== 0) {
    throw new Error('module_operation_failed');
  }

  let response;
  try {
    response = JSON.parse(result.stdout);
  } catch {
    throw new Error('module_response_invalid');
  }
  if (response?.ok === false) {
    throw new Error('module_operation_failed');
  }
  if (!response || typeof response !== 'object' || Array.isArray(response) ||
      !Number.isInteger(response.schemaVersion) || response.schemaVersion < 1 || response.ok !== true ||
      !response.state || typeof response.state !== 'object' || Array.isArray(response.state) ||
      typeof response.state.phase !== 'string' || response.state.phase.trim() === '') {
    throw new Error('module_response_invalid');
  }
  return response;
}

function displayValue(value) {
  if (typeof value === 'string' && value.length > 0) return value;
  if (typeof value === 'number' && Number.isFinite(value)) return String(value);
  return '未知';
}

export function eventSummary(state) {
  return Array.isArray(state?.events)
    ? state.events.slice(-40).map(event => ({
      time: Number.isFinite(event?.time) ? event.time : null,
      code: typeof event?.code === 'string' ? event.code : 'unknown_event',
    }))
    : [];
}

export function stateView(state) {
  const revision = typeof state.runningRevision === 'string' && state.runningRevision
    ? state.runningRevision
    : state.revision;
  const events = eventSummary(state);

  return {
    phase: displayValue(state.phase),
    health: displayValue(state.health),
    profile: displayValue(state.profileName),
    revision: typeof revision === 'string' && revision ? revision.slice(0, 12) : '未知',
    retryCount: Number.isInteger(state.retryCount) && state.retryCount >= 0
      ? String(state.retryCount)
      : '未知',
    error: typeof state.error === 'string' ? state.error : '',
    events,
  };
}
