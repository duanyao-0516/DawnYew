#!/usr/bin/env node
'use strict';

// Read-only, offline verification. Never execute the HTML or its UI/event scripts.
// The SHA-256 gate runs before the reviewed constants/pure functions enter a VM.
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const COMMIT = '6c24c9f2a4d3548010af44c9e1322c206f64f73d';
const SOURCE_SHA256 = 'bfc7bbe66ae9a361220b2816651250b8769b0158c6aab8da882e6f8b2f22cea5';
const SOURCE_URL = `https://github.com/UnluckyNinja/SBTI-test/blob/${COMMIT}/index.html`;
// Original image digests recorded at the same pinned commit.
const IMAGE_SHA256 = {
  "IMSB": "c76f1b460cd4ea5dc5ff802d84619b6e2d22bdcfccdeb25ad977b476dd30d434",
  "BOSS": "15835541156378cc99aac0e53dfb9300fd663db52c37bfbfcaa9f18cb228b355",
  "MUM": "4781faa501be7c6c8448622d98e44bc940c3ee1ea51c9b7f9f4bcf4a48558607",
  "FAKE": "11e1bb8e81625e60b9cba219d1f9fe61bc5bd98516b40a5b8c371ccae779f2ea",
  "Dior-s": "01ad9e4561add759852e4e2f1aeee4360c1f9c53fc39fc96ed66d2ed81eb8547",
  "DEAD": "36a01e7dc94556a9725f436c77ee5d52ed34be494963efc0154875d0a7d90a6a",
  "ZZZZ": "566fc774c38e3a8c4ec52f243ef400fefda753184fd93bfb99bf9d5d60ca5271",
  "GOGO": "4b74a1db85e503e11009e5a6b3670afffe71b698a1a059858ed9c6f34744e333",
  "FUCK": "e325278c37ce7599af94ed2ac3da934654ac65a0906a4ba8c27e3076e5221e34",
  "CTRL": "761aeb9803c466ffa4786fbb123d7af58e5b3c9d4eeff2d5172178bd2976e488",
  "HHHH": "3f9b8ca33302a6d5710e8725dbc5db2a4e71fff82830147a3d710a3bc5e693b7",
  "SEXY": "43f7da99802985e13c6c2130f4808d60c1ee1575a4d0a6c7026b0c1298b35e4e",
  "OJBK": "c4bd40603e63f9abbc82f9b64be9f93a9bf4e7e45ca2e04097e54a7108706728",
  "JOKE-R": "71cd777402a175262ec066bac91b3bed822278bd9f5f2dfcafc36249d38bff78",
  "POOR": "b491d139ab6ee97009e0536b7abd80089c3131d7ac8dbd9d5e3f59bfe81de043",
  "OH-NO": "4cfa50279423f07908aff7b49c25a5a4445a585d0f9351dbdd6d8741afd64b79",
  "MONK": "322a4be5f3b8c83ca82e996481570fa509ee819c46810bc18b2425279780ad1e",
  "SHIT": "1853e7edd9c436f354e30e426c77fa1a6044e48227287fce45516944fa6b9fba",
  "THAN-K": "306a033d0ce6344c5bdbceb388d284f7786db8c43f06ad8557e2e341715ee2f2",
  "MALO": "1af0f71c77853d8c9d1121581218f62423d635b52980e5f14dbc93592edb5f9a",
  "ATM-er": "8838ddd4db8408b7d0c2d16b98d00a7e7634f238050812343c7ba88c34c3ffd3",
  "THIN-K": "4d0614454d4566ea1a3b446c6705441aad3e12a406d2de4d3ffc76f35c7a920c",
  "SOLO": "6e89bd226e9292777bdd1e44bdf33bb07278fb5b7bc14dfe4567790e9b58683b",
  "LOVE-R": "a576c51c7f8162ba028bd92ebeb08fdd832ad4346c3b0f41eaddb36b61843f81",
  "WOC!": "26fc7ef737e1353673188f827b3ff18f693603ad05ed920516aa710f4b85b918",
  "DRUNK": "52c9fd903d4cefdb3bbf539092928ae02ba653d18a4c8b941448a69eb1afb2a2",
  "IMFW": "2079f1d17ec519fe0dee62c059f8c01a9cbb0d20617a7360b548564c6597a156"
};
const ROOT = path.resolve(__dirname, '..');
const CORE = 'shared/core/src';
const PACKAGE = 'kotlin/com/dawnyew/core/personality';
const sha256 = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const kotlinString = text => JSON.stringify(text).replace(/\$/g, '\\$');

function reviewedRange(html, start, end) {
  const first = html.indexOf(start);
  const last = html.indexOf(end, first + start.length);
  assert(first >= 0 && last > first, `Missing reviewed source boundary: ${start}`);
  assert.equal(html.indexOf(start, first + start.length), -1, `Ambiguous source boundary: ${start}`);
  return html.slice(first, last);
}

function extract(sourceDirectory) {
  const bytes = fs.readFileSync(path.join(sourceDirectory, 'index.html'));
  assert.equal(sha256(bytes), SOURCE_SHA256, 'index.html is not the reviewed, pinned source');
  const html = bytes.toString('utf8');
  const constants = reviewedRange(html, '    const dimensionMeta =', '    const app =');
  const functions = reviewedRange(html, '    function sumToLevel', '    function renderDimList');
  const context = vm.createContext(Object.create(null), {
    codeGeneration: { strings: false, wasm: false },
  });
  // These ranges only define literal data and the reviewed numerical scorer.
  // There is no DOM, browser, network, process, require, timer or filesystem API.
  new vm.Script(`${constants}\nconst app = { answers: {} };\n${functions}`).runInContext(context, { timeout: 1000 });
  const data = JSON.parse(new vm.Script(`JSON.stringify({
    dimensionMeta, questions, specialQuestions, TYPE_LIBRARY, TYPE_IMAGES,
    NORMAL_TYPES, DIM_EXPLANATIONS, dimensionOrder
  })`).runInContext(context, { timeout: 1000 }));
  const run = new vm.Script('app.answers = input; JSON.stringify(computeResult());');
  function score(answers) {
    context.input = answers;
    return JSON.parse(run.runInContext(context, { timeout: 1000 }));
  }
  assert.equal(data.questions.length, 30);
  assert.equal(data.specialQuestions.length, 2);
  assert.equal(Object.keys(data.TYPE_LIBRARY).length, 27);
  assert.equal(data.NORMAL_TYPES.length, 25);
  assert.equal(data.dimensionOrder.length, 15);
  assert.equal(Object.values(data.DIM_EXPLANATIONS).flatMap(Object.keys).length, 45);
  assert.deepEqual(data.questions.filter(q => q.options[0].value === 3).map(q => q.id), ['q14', 'q27']);
  for (const id of data.dimensionOrder) assert.equal(data.questions.filter(q => q.dim === id).length, 2);
  return { data, score };
}

function canonicalCatalog(d) {
  return [
    ...[...d.questions, ...d.specialQuestions].map(q => [q.id, q.text, q.dim || '', ...q.options.flatMap(o => [o.label, String(o.value)])].join('\t')),
    ...Object.values(d.TYPE_LIBRARY).map(t => [t.code, t.cn, t.intro, t.desc, path.basename(d.TYPE_IMAGES[t.code])].join('\t')),
    ...d.dimensionOrder.map(id => [id, d.dimensionMeta[id].name, d.dimensionMeta[id].model, ...['L', 'M', 'H'].map(l => d.DIM_EXPLANATIONS[id][l])].join('\t')),
    ...d.NORMAL_TYPES.map(t => [t.code, t.pattern].join('\t')),
  ].join('\n');
}

function renderCatalog(d) {
  function question(q, indent) {
    return `${indent}SbtiQuestion(\n${indent}    id = ${kotlinString(q.id)},\n${indent}    text = ${kotlinString(q.text)},\n${indent}    dimensionId = ${q.dim ? kotlinString(q.dim) : 'null'},\n${indent}    options = listOf(\n` +
      q.options.map(o => `${indent}        SbtiOption(${kotlinString(o.label)}, ${o.value}),`).join('\n') +
      `\n${indent}    ),\n${indent})`;
  }
  const questions = d.questions.map(q => question(q, '        ') + ',').join('\n');
  const types = Object.values(d.TYPE_LIBRARY).map(t =>
    `        SbtiType(\n            code = ${kotlinString(t.code)},\n            name = ${kotlinString(t.cn)},\n            intro = ${kotlinString(t.intro)},\n            description = ${kotlinString(t.desc)},\n            imageFile = ${kotlinString(path.basename(d.TYPE_IMAGES[t.code]))},\n        ),`).join('\n');
  const dimensions = d.dimensionOrder.map(id =>
    `        SbtiDimension(\n            id = ${kotlinString(id)},\n            name = ${kotlinString(d.dimensionMeta[id].name)},\n            model = ${kotlinString(d.dimensionMeta[id].model)},\n            explanations = mapOf(\n` +
    ['L', 'M', 'H'].map(level => `                ${kotlinString(level)} to ${kotlinString(d.DIM_EXPLANATIONS[id][level])},`).join('\n') +
    '\n            ),\n        ),').join('\n');
  const templates = d.NORMAL_TYPES.map(t => `        SbtiTemplate(${kotlinString(t.code)}, ${kotlinString(t.pattern)}),`).join('\n');
  return `package com.dawnyew.core.personality

// Literal import of the pinned source; verify with scripts/verify-sbti-source.cjs.
// Original wording, option values, template order and image filenames are retained.
object SbtiCatalog {
    const val SOURCE_URL = ${kotlinString(SOURCE_URL)}
    const val SOURCE_COMMIT = ${kotlinString(COMMIT)}
    const val SOURCE_SHA256 = ${kotlinString(SOURCE_SHA256)}

    val questions: List<SbtiQuestion> = listOf(
${questions}
    )

    val hobbyQuestion =
${question(d.specialQuestions[0], '        ')}

    val drinkQuestion =
${question(d.specialQuestions[1], '        ')}

    val types: List<SbtiType> = listOf(
${types}
    )

    internal val dimensions: List<SbtiDimension> = listOf(
${dimensions}
    )

    internal val normalTemplates: List<SbtiTemplate> = listOf(
${templates}
    )
}
`;
}

function oracleCases(d, score) {
  const cases = [];
  const findings = {};
  const answersByLevels = pattern => Object.fromEntries(d.questions.map(q =>
    [q.id, { L: 1, M: 2, H: 3 }[pattern.replace(/-/g, '')[d.dimensionOrder.indexOf(q.dim)]]]
  ).concat([['drink_gate_q1', 1]]));
  function add(id, answers) { cases.push({ id, answers, result: score(answers) }); }
  for (let value = 1; value <= 3; value++) add(`all-score-${value}`, Object.fromEntries(d.questions.map(q => [q.id, value]).concat([['drink_gate_q1', 1]])));
  for (let index = 0; index < 3; index++) add(`all-option-${String.fromCharCode(65 + index)}`, Object.fromEntries(d.questions.map(q => [q.id, q.options[index].value]).concat([['drink_gate_q1', 1]])));
  for (const type of d.NORMAL_TYPES) add(`exact-template-${type.code}`, answersByLevels(type.pattern));
  let seed = 0x5B71;
  const random = () => { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return seed / 0x100000000; };
  const randomAnswers = () => Object.fromEntries(d.questions.map(q => [q.id, 1 + Math.floor(random() * 3)]).concat([['drink_gate_q1', 1]]));
  for (let iteration = 0; iteration < 100000 && Object.keys(findings).length < 4; iteration++) {
    const answers = randomAnswers();
    const result = score(answers);
    const [first, second] = result.ranked;
    const criteria = {
      'fallback-below-60': first.similarity < 60,
      'normal-at-60': first.similarity === 60,
      'tie-distance-exact-decides': first.distance === second.distance && first.exact > second.exact,
      'tie-stable-library-order': first.distance === second.distance && first.exact === second.exact,
    };
    for (const [name, matches] of Object.entries(criteria)) {
      if (matches && !findings[name]) { findings[name] = answers; add(name, answers); }
    }
  }
  assert.equal(Object.keys(findings).length, 4, 'Expected boundary/tie cases were not found');
  add('DRUNK-overrides-normal', { ...answersByLevels(d.NORMAL_TYPES[0].pattern), drink_gate_q1: 3, drink_gate_q2: 2 });
  add('DRUNK-overrides-fallback', { ...findings['fallback-below-60'], drink_gate_q1: 3, drink_gate_q2: 2 });
  add('drink-without-DRUNK', { ...answersByLevels(d.NORMAL_TYPES[0].pattern), drink_gate_q1: 3, drink_gate_q2: 1 });
  for (let index = 0; index < 256; index++) add(`seeded-random-${String(index).padStart(3, '0')}`, randomAnswers());
  assert.equal(cases.length, 294);
  return cases;
}

function renderOracle(d, cases) {
  const lines = cases.map(({ id, answers, result }) => [
    id,
    Array.from({ length: 30 }, (_, i) => answers[`q${i + 1}`]).join('') + answers.drink_gate_q1 + (answers.drink_gate_q2 || ''),
    result.finalType.code,
    result.finalType.code === 'DRUNK' ? 'DRUNK' : result.finalType.code === 'HHHH' ? 'FALLBACK' : 'NORMAL',
    d.dimensionOrder.map(id => result.rawScores[id]).join(''),
    d.dimensionOrder.map(id => result.levels[id]).join(''),
    result.ranked.map(r => [r.code, r.distance, r.exact, r.similarity].join(',')).join('|'),
  ].join(';'));
  return `package com.dawnyew.core.personality

// Recorded from the pinned original JavaScript, independently of Kotlin scoring.
// scripts/verify-sbti-source.cjs reproduces all cases without executing the webpage.
internal object SbtiSourceOracle {
    const val catalogSha256 = ${kotlinString(sha256(canonicalCatalog(d)))}
    val cases = listOf(
${lines.map(line => `        ${kotlinString(line)},`).join('\n')}
    )
}
`;
}

function verify(sourceDirectory) {
  const { data, score } = extract(sourceDirectory);
  const catalog = fs.readFileSync(path.join(ROOT, CORE, 'commonMain', PACKAGE, 'SbtiCatalog.kt'), 'utf8');
  assert.equal(catalog, renderCatalog(data), 'Kotlin catalog differs from the pinned source');
  const cases = oracleCases(data, score);
  const oracle = fs.readFileSync(path.join(ROOT, CORE, 'commonTest', PACKAGE, 'SbtiSourceOracle.kt'), 'utf8');
  assert.equal(oracle, renderOracle(data, cases), 'Recorded Kotlin oracle differs from original JavaScript output');
  const assets = Object.entries(data.TYPE_IMAGES).map(([code, relative]) => {
    const original = fs.readFileSync(path.join(sourceDirectory, relative));
    assert.equal(sha256(original), IMAGE_SHA256[code], `Original image is not the pinned asset: ${code}`);
    const file = `sbti_${path.basename(relative).toLowerCase().replace(/-/g, '_')}`;
    const packaged = fs.readFileSync(path.join(ROOT, 'shared/ui/src/commonMain/composeResources/drawable', file));
    assert.equal(sha256(packaged), sha256(original), `Image bytes differ: ${code} -> ${file}`);
    return { code, file, bytes: original.length, sha256: sha256(original) };
  });
  assert.equal(assets.length, 27);
  return { sourceCommit: COMMIT, sourceSha256: SOURCE_SHA256, regularQuestions: data.questions.length,
    specialQuestions: data.specialQuestions.length, types: Object.keys(data.TYPE_LIBRARY).length,
    templates: data.NORMAL_TYPES.length, dimensions: data.dimensionOrder.length, explanations: 45,
    oracleCases: cases.length, images: assets.length, imageBytes: assets.reduce((sum, a) => sum + a.bytes, 0),
    catalogSha256: sha256(canonicalCatalog(data)), readOnly: true };
}

if (require.main === module) {
  const args = process.argv.slice(2);
  if (args.length !== 2 || args[0] !== '--source-dir') {
    process.stderr.write('Usage: node scripts/verify-sbti-source.cjs --source-dir /path/to/pinned/SBTI-test\n');
    process.exitCode = 2;
  } else {
    try { process.stdout.write(JSON.stringify(verify(path.resolve(args[1])), null, 2) + '\n'); }
    catch (error) { process.stderr.write(`${error.message}\n`); process.exitCode = 1; }
  }
}

// Pure renderers are exported for initial import tooling; this command never writes files.
module.exports = { extract, canonicalCatalog, renderCatalog, oracleCases, renderOracle, verify };
