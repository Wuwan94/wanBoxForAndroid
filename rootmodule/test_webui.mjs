import assert from 'node:assert/strict';
import test from 'node:test';

import { eventSummary, runAction } from './package/webroot/controller.mjs';

const cli = '/data/adb/modules/wanbox/bin/wanboxctl';

test('WebUI dispatches only fixed status and lifecycle commands', async () => {
  const calls = [];
  const response = await runAction('restart', async command => {
    calls.push(command);
    return { errno: 0, stdout: '{"schemaVersion":1,"ok":true,"state":{"phase":"running"}}', stderr: '' };
  });

  assert.deepEqual(calls, [`${cli} restart`]);
  assert.deepEqual(response, { schemaVersion: 1, ok: true, state: { phase: 'running' } });
});

test('WebUI rejects unknown action names before invoking the privileged bridge', async () => {
  let called = false;
  await assert.rejects(
    runAction('start; rm -rf /', async () => {
      called = true;
      return { errno: 0, stdout: '{}', stderr: '' };
    }),
    error => error.message === 'action_not_allowed',
  );
  assert.equal(called, false);
});

test('WebUI does not expose privileged command stderr to the page', async () => {
  await assert.rejects(
    runAction('stop', async () => ({
      errno: 1,
      stdout: '{"error":"module_operation_failed"}',
      stderr: 'private path and secret material',
    })),
    error => error.message === 'module_operation_failed' && !error.message.includes('secret'),
  );
});

test('WebUI rejects malformed command output with a stable error', async () => {
  await assert.rejects(
    runAction('status', async () => ({ errno: 0, stdout: 'not-json', stderr: '' })),
    error => error.message === 'module_response_invalid',
  );
});

test('WebUI command table contains only the intended read and lifecycle actions', async () => {
  const calls = [];
  for (const [action, expected] of [
    ['status', 'status'],
    ['dashboard', 'dashboard'],
    ['logs', 'logs'],
    ['start', 'start'],
    ['stop', 'stop'],
    ['restart', 'restart'],
  ]) {
    await runAction(action, async command => {
      calls.push(command);
      return { errno: 0, stdout: '{"schemaVersion":1,"ok":true,"state":{"phase":"stopped"}}', stderr: '' };
    });
    assert.equal(calls.at(-1), `${cli} ${expected}`);
  }
  assert.equal(calls.length, 6);
});

test('WebUI treats a failed JSON envelope as an operation failure', async () => {
  await assert.rejects(
    runAction('start', async () => ({
      errno: 0,
      stdout: '{"ok":false,"error":"start_failed","state":{}}',
      stderr: '',
    })),
    error => error.message === 'module_operation_failed',
  );
});

test('WebUI rejects structurally incomplete status responses', async () => {
  await assert.rejects(
    runAction('status', async () => ({ errno: 0, stdout: '{"schemaVersion":1,"ok":true}', stderr: '' })),
    error => error.message === 'module_response_invalid',
  );
});

test('WebUI rejects a successful envelope with an empty or malformed state phase', async () => {
  for (const state of [{}, { phase: 7 }, { phase: '' }]) {
    await assert.rejects(
      runAction('status', async () => ({
        errno: 0,
        stdout: JSON.stringify({ schemaVersion: 1, ok: true, state }),
        stderr: '',
      })),
      error => error.message === 'module_response_invalid',
    );
  }
});

test('WebUI rejects successful envelopes missing schemaVersion or ok', async () => {
  for (const envelope of [
    { ok: true, state: { phase: 'running' } },
    { schemaVersion: 1, state: { phase: 'running' } },
  ]) {
    await assert.rejects(
      runAction('status', async () => ({ errno: 0, stdout: JSON.stringify(envelope), stderr: '' })),
      error => error.message === 'module_response_invalid',
    );
  }
});

test('WebUI state view keeps only fixed display fields and the newest forty events', async () => {
  const { stateView } = await import('./package/webroot/controller.mjs');
  const events = Array.from({ length: 45 }, (_, index) => ({ time: index + 1, code: `event-${index + 1}` }));
  const view = stateView({
    phase: 'running',
    health: 'degraded',
    profileName: '<img src=x onerror=alert(1)>',
    runningRevision: '0123456789abcdef',
    retryCount: 2,
    events,
    privateField: 'must not render',
  });

  assert.equal(view.phase, 'running');
  assert.equal(view.health, 'degraded');
  assert.equal(view.profile, '<img src=x onerror=alert(1)>');
  assert.equal(view.revision, '0123456789ab');
  assert.equal(view.retryCount, '2');
  assert.equal(view.events.length, 40);
  assert.equal(view.events[0].code, 'event-6');
  assert.equal(view.events.at(-1).code, 'event-45');
  assert.equal(Object.hasOwn(view, 'privateField'), false);
});

test('WebUI event export summary keeps only time and code for the newest forty events', () => {
  const events = Array.from({ length: 45 }, (_, index) => ({
    time: index + 1,
    code: `event-${index + 1}`,
    privateField: `secret-${index + 1}`,
  }));
  const summary = eventSummary({ events });

  assert.equal(summary.length, 40);
  assert.deepEqual(summary[0], { time: 6, code: 'event-6' });
  assert.deepEqual(summary.at(-1), { time: 45, code: 'event-45' });
  assert.deepEqual(Object.keys(summary[0]).sort(), ['code', 'time']);
});

async function openWebUI(context, manager) {
  const element = (dataset = {}) => ({
    disabled: false, dataset, textContent: '', children: [],
    addEventListener(type, listener) { this[type] = listener; },
    replaceChildren() { this.children = []; },
    append(...children) { this.children.push(...children); },
  });
  const actionButtons = ['logs', 'start', 'stop', 'restart'].map(action => element({ action }));
  const nodes = new Map();
  const document = {
    getElementById(id) {
      if (!nodes.has(id)) nodes.set(id, element());
      return nodes.get(id);
    },
    querySelectorAll(selector) {
      assert.equal(selector, '[data-action]');
      return actionButtons;
    },
    createElement: () => element(),
  };
  const originals = ['document', 'ksu'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]);
  context.after(() => {
    for (const [key, descriptor] of originals) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor);
      else delete globalThis[key];
    }
  });
  globalThis.document = document;
  if (manager === undefined) delete globalThis.ksu;
  else globalThis.ksu = manager;
  await import(`./package/webroot/app.mjs?test=${encodeURIComponent(context.name)}`);
  return { nodes, buttons: [...actionButtons, nodes.get('export-events')] };
}

test('WebUI without a manager fails closed and disables every action', async context => {
  const { nodes, buttons } = await openWebUI(context, undefined);
  assert.match(nodes.get('runtime-message').textContent, /未检测到兼容/);
  assert.ok(buttons.every(button => button.disabled));
});

test('WebUI uses the injected manager to load logs and releases each callback', async context => {
  const commands = [];
  const callbackNames = [];
  const manager = {
    exec(command, options, callbackName) {
      assert.equal(this, manager);
      assert.deepEqual(JSON.parse(options), {});
      assert.match(callbackName, /^[A-Za-z_$][\w$]*$/);
      assert.equal(typeof globalThis[callbackName], 'function');
      commands.push(command);
      callbackNames.push(callbackName);
      queueMicrotask(() => globalThis[callbackName](0,
        '{"schemaVersion":1,"ok":true,"state":{"phase":"running"}}', ''));
    },
  };
  const { nodes, buttons } = await openWebUI(context, manager);
  assert.deepEqual(commands, [`${cli} logs`]);
  assert.match(nodes.get('runtime-message').textContent, /已连接管理器/);
  assert.equal(nodes.get('phase-badge').dataset.phase, 'running');
  assert.ok(buttons.every(button => !button.disabled));
  await buttons[0].click();
  assert.deepEqual(commands, [`${cli} logs`, `${cli} logs`]);
  assert.equal(new Set(callbackNames).size, 2);
  assert.ok(callbackNames.every(name => !Object.hasOwn(globalThis, name)));
});
