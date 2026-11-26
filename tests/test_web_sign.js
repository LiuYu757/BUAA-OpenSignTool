const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function setup(result) {
    const buttons = [{disabled: false}, {disabled: false}];
    const card = {_courseData: {id: 'one', courseName: 'Course', courseSchedIds: ['one', 'two'], signStatus: '0'},
        querySelectorAll: () => buttons};
    const batch = {disabled: false};
    const lateBatch = {disabled: false};
    const calls = [];
    const toasts = [];
    const context = vm.createContext({window: {addEventListener() {}, pywebview: {api: {
        sign_course: (...args) => { calls.push(args); return Promise.resolve(result); },
        batch_sign_week: (...args) => { calls.push(args); return Promise.resolve(result); },
    }}}});
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../web/app.js'), 'utf8'), context);
    const app = context.window.app;
    app.toast = text => toasts.push(text);
    app.pushLog = () => {};
    app.$ = id => id === 'btnBatch' ? batch : lateBatch;
    app.getSemester = () => ({year: 2026, month: 9, day: 1});
    app._refreshCardsByResult = () => {};
    return {app, buttons, card, batch, lateBatch, calls, toasts};
}

test('late click sends mode and all merged IDs; failure unlocks both buttons', async () => {
    const s = setup({success: 0, skipped: 0, failed: 2, results: [{status: 'failed', message: '课程已结束'}]});
    const pending = s.app.signCourseByCard(s.card, 'late');
    assert(s.buttons.every(b => b.disabled));
    assert.equal(s.buttons[1].textContent, '提交中…');
    await pending;
    assert.equal(s.calls[0][2], 'late');
    assert.deepEqual(JSON.parse(s.calls[0][0]), ['one', 'two']);
    assert.deepEqual(JSON.parse(s.calls[0][1]), ['Course', 'Course']);
    assert.deepEqual(s.toasts, ['课程已结束']);
    assert(s.buttons.every(b => !b.disabled));
    assert.equal(s.app._signingCourse, null);
    assert.equal(s.card._courseData.signStatus, '0');
});

test('iClass success updates attendance and displays the actual response', async () => {
    const s = setup({success: 1, skipped: 0, results: [{status: 'success', message: '迟到签到成功'}]});
    let updates = 0;
    s.app._updateCardSigned = () => { updates++; s.card._courseData.signStatus = '1'; };
    await s.app.signCourseByCard(s.card, 'late');
    assert.equal(updates, 1);
    assert.deepEqual(s.toasts, ['迟到签到成功']);
});

test('late batch disables both batch actions and restores them with an empty result message', async () => {
    const s = setup({success: 0, skipped: 0, total: 0, results: [], message: '当前没有可迟到签到的课程'});
    const pending = s.app.batchSign('late');
    assert(s.batch.disabled && s.lateBatch.disabled);
    await pending;
    assert.equal(s.calls[0][4], 'late');
    assert(!s.batch.disabled && !s.lateBatch.disabled);
    assert.deepEqual(s.toasts, ['当前没有可迟到签到的课程']);
});

test('normal action continues to send normal mode', async () => {
    const s = setup({success: 0, skipped: 0, failed: 1, results: [{status: 'failed', message: '未到签到时间'}]});
    await s.app.signCourseByCard(s.card);
    assert.equal(s.calls[0][2], 'normal');
});

test('language initialization resolves real HTML IDs including late actions', () => {
    const s = setup({});
    const html = fs.readFileSync(path.join(__dirname, '../web/index.html'), 'utf8');
    const elements = new Map([...html.matchAll(/id="([^"]+)"/g)].map(m => [m[1], {}]));
    s.app.$ = id => elements.get(id);
    s.app.applyLanguage();
    assert.equal(elements.get('btnBatchLate').textContent, '一键迟到签到');
    s.app.lang = 'en';
    s.app.applyLanguage();
    assert.equal(elements.get('btnBatchLate').textContent, 'Late Check-in All');
    assert.equal(elements.get('logoutBtn').textContent, 'Sign Out');
});
