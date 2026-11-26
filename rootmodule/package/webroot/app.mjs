import { eventSummary, runAction, stateView } from './controller.mjs';

const byId = id => document.getElementById(id);
const runtimeMessage = byId('runtime-message');
const operationMessage = byId('operation-message');
const buttons = [...document.querySelectorAll('[data-action]'), byId('export-events')];
let executeCommand = null;
let busy = false;

const actionLabels = {
  logs: '刷新状态',
  start: '启动',
  stop: '停止',
  restart: '重启',
};

const errorMessages = {
  action_not_allowed: '不支持此操作。',
  module_bridge_unavailable: '当前管理器没有提供可用的 KernelSU WebUI 接口。',
  module_operation_failed: '模块操作失败；请检查模块状态后重试。',
  module_response_invalid: '模块返回了无法识别的状态。',
};

function setBusy(value) {
  busy = value;
  for (const button of buttons) button.disabled = busy || !executeCommand;
}

function safeErrorMessage(error) {
  return errorMessages[error?.message] || '操作未完成，请稍后重试。';
}

function renderState(state) {
  const view = stateView(state);
  const badge = byId('phase-badge');
  badge.textContent = view.phase;
  badge.dataset.phase = ['running', 'stopped', 'disabled', 'degraded', 'failed'].includes(state.phase)
    ? state.phase
    : 'unknown';
  byId('health').textContent = view.health;
  byId('profile').textContent = view.profile;
  byId('revision').textContent = view.revision;
  byId('retry-count').textContent = view.retryCount;

  const stateError = byId('state-error');
  stateError.textContent = view.error;
  stateError.hidden = !view.error;

  const list = byId('events');
  list.replaceChildren();
  for (const event of view.events) {
    const item = document.createElement('li');
    const time = document.createElement('span');
    const code = document.createElement('code');
    time.className = 'event-time';
    code.className = 'event-code';
    time.textContent = event.time === null ? '时间未知' : new Date(event.time * 1000).toLocaleString();
    code.textContent = event.code;
    item.append(time, code);
    list.append(item);
  }
  byId('events-empty').hidden = view.events.length > 0;
}

async function readEvents() {
  const response = await runAction('logs', executeCommand);
  renderState(response.state);
  return response.state;
}

async function perform(action) {
  if (!executeCommand || busy) return;
  setBusy(true);
  operationMessage.textContent = `正在${actionLabels[action] || '执行操作'}…`;
  try {
    const response = await runAction(action, executeCommand);
    renderState(response.state);
    if (action !== 'logs') await readEvents();
    operationMessage.textContent = `${actionLabels[action] || '操作'}已完成。`;
  } catch (error) {
    operationMessage.textContent = safeErrorMessage(error);
  } finally {
    setBusy(false);
  }
}

function downloadEvents(state) {
  const events = eventSummary(state);
  const payload = {
    exportedAt: new Date().toISOString(),
    source: 'wanbox-root-webui-events',
    events,
  };
  const blob = new Blob([`${JSON.stringify(payload, null, 2)}\n`], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = 'wanbox-events.json';
  link.click();
  URL.revokeObjectURL(url);
}

async function exportEvents() {
  if (!executeCommand || busy) return;
  setBusy(true);
  operationMessage.textContent = '正在读取有界事件…';
  try {
    const state = await readEvents();
    downloadEvents(state);
    operationMessage.textContent = '最近事件已导出；原始日志不会包含在此文件中。';
  } catch (error) {
    operationMessage.textContent = safeErrorMessage(error);
  } finally {
    setBusy(false);
  }
}

for (const button of document.querySelectorAll('[data-action]')) {
  button.addEventListener('click', () => perform(button.dataset.action));
}
byId('export-events').addEventListener('click', exportEvents);

try {
  const managerApi = await import('kernelsu');
  if (typeof managerApi.exec !== 'function') throw new Error('module_bridge_unavailable');
  executeCommand = managerApi.exec;
  runtimeMessage.textContent = '已连接管理器 WebUI 接口；仅调用固定 wanboxctl 命令。';
  setBusy(false);
  await perform('logs');
} catch {
  runtimeMessage.textContent = '未检测到兼容的管理器 WebUI 接口。请从 KernelSU 或兼容其 WebUI API 的 APatch 打开；Magisk 本身不提供原生 WebUI。';
  operationMessage.textContent = '第三方运行时须另行验证；本页不会尝试任意 shell 或网络请求。';
  setBusy(false);
}
