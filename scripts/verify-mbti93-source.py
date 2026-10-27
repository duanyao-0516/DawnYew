#!/usr/bin/env python3
"""Verify the checked-in MyMBTI import against an already downloaded, pinned HTML.

No network access. Only the reviewed scoring function and the pure type/name prefix
are evaluated in a Node VM with a checked-only document stub; the HTML, timers,
navigation, result rendering and other scripts are never executed.
"""

import argparse
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import subprocess


COMMIT = "492f8e08a6c5ab3a68f60ad24618e3b96ddf1dde"
SOURCE_SHA256 = "8cbc7165f12b94114a9f1e085d0dca66eaa7c87735a97af2444b4ec4179c78b9"
ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "shared/core/src/{}/kotlin/com/dawnyew/core/personality"
LETTERS = "EISNTFJP"


class Labels(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.labels = {}
        self.current = None

    def handle_starttag(self, tag, attrs):
        if tag == "label":
            label_id = dict(attrs).get("id", "")
            if re.fullmatch(r"question\d+_lbl(?:_[AB])?", label_id):
                assert label_id not in self.labels, label_id
                self.current = label_id
                self.labels[label_id] = ""

    def handle_data(self, data):
        if self.current:
            self.labels[self.current] += data

    def handle_endtag(self, tag):
        if tag == "label":
            self.current = None


def extract(source):
    labels = Labels()
    labels.feed(source)
    assert len(labels.labels) == 279
    table = re.search(r"var MBTI_Questions = `(.*?)`;", source, re.S).group(1)
    polarities = {}
    for line in table.strip().splitlines():
        number, _, mapping = line.strip().split("\t")
        first, second = re.fullmatch(r"a:([EISNTFJP]),\s*b:([EISNTFJP])", mapping).groups()
        assert LETTERS.index(first) // 2 == LETTERS.index(second) // 2 and first != second
        polarities[int(number)] = first
    assert sorted(polarities) == list(range(1, 94))
    questions = []
    for number in range(1, 94):
        fields = []
        for suffix, prefix in [("", str(number)), ("_A", "A"), ("_B", "B")]:
            visible = re.sub(r"\s+", " ", labels.labels[f"question{number}_lbl{suffix}"]).strip()
            assert visible.startswith(prefix + " "), visible
            fields.append(visible[len(prefix):].strip())
        questions.append((number, *fields, polarities[number]))
    return questions


def oracle(source, answer_sets):
    scoring = source.split("function myMBTItest()", 1)[1].split("</script>", 1)[0].strip()
    scoring = "function myMBTItest()" + scoring
    type_prefix = source.split("function showTotal8type(){", 1)[1].split("var currentDate", 1)[0]
    type_function = "function sourceType(){" + type_prefix + "return {type: mbtiType, name: mbtiTypecn};}"
    name_function = "function getChinesePersonality" + source.split("function getChinesePersonality", 1)[1].split("// 自定义 confirm 弹框", 1)[0]
    driver = r"""
const fs = require('node:fs');
const vm = require('node:vm');
const input = JSON.parse(fs.readFileSync(0, 'utf8'));
let answers;
const sandbox = {
  console: { log() {} },
  document: { getElementById(id) {
    const match = /^option(\d+)_([1-5])$/.exec(id);
    if (!match) throw new Error('Unexpected DOM access: ' + id);
    return { checked: answers[Number(match[1]) - 1] === Number(match[2]) };
  } }
};
const context = vm.createContext(sandbox, { codeGeneration: { strings: false, wasm: false } });
new vm.Script(input.code).runInContext(context, { timeout: 1000 });
const run = new vm.Script('myMBTItest(); sourceType();');
const results = input.answers.map(values => {
  answers = values;
  const result = run.runInContext(context, { timeout: 1000 });
  return { ...result, scores: [...'EISNTFJP'].map(letter => sandbox['m_type_' + letter]), neutral: sandbox.lMissS };
});
process.stdout.write(JSON.stringify(results));
"""
    result = subprocess.run(["node", "-e", driver], input=json.dumps({"code": scoring + "\n" + type_function + "\n" + name_function, "answers": answer_sets}), text=True, capture_output=True, check=True)
    return json.loads(result.stdout)


def fixtures(source, questions):
    singles = [[value if index == question else 3 for index in range(93)] for question in range(93) for value in range(1, 6)]
    mixed = [[value] * 93 for value in range(1, 6)]
    mixed += [[1 if index % 2 == 0 else 5 for index in range(93)], [5 if index % 2 == 0 else 1 for index in range(93)]]
    tied = [1] * 93
    for question, value in {65: 4, 75: 5, 81: 5, 83: 5, 84: 4, 86: 5, 87: 5, 88: 5, 90: 5, 91: 4, 93: 5}.items():
        tied[question - 1] = value
    mixed.append(tied)
    # Select A or B to exercise each type. Expected outputs still come from source JS.
    for e in "EI":
        for s in "SN":
            for t in "TF":
                for j in "JP":
                    target = e + s + t + j
                    mixed.append([1 if q[4] in target else 5 for q in questions])
    # Reproducible mixed answers also include neutral and both intermediate choices.
    state = 20261027
    for _ in range(16):
        values = []
        for _ in range(93):
            state = (1664525 * state + 1013904223) & 0xFFFFFFFF
            values.append(state % 5 + 1)
        mixed.append(values)
    results = oracle(source, singles + mixed)
    canonical = "\n".join("\t".join(map(str, q)) for q in questions)
    digest = hashlib.sha256(canonical.encode()).hexdigest()
    text = """package com.dawnyew.core.personality

// Generated from the pinned source's actual JS by scripts/verify-mbti93-source.py.
// Do not replace these recorded outputs with a second implementation of scoring.
internal object Mbti93SourceOracle {
    const val catalogSha256 = "%s"
    val isolatedScores = \"\"\"
%s
    \"\"\".trimIndent().lines().flatMap { row ->
        row.split('|').map { scores -> scores.map { it.digitToInt() } }
    }
    // answers ; type ; name ; E,I,S,N,T,F,J,P ; neutral count
    val completeCases = \"\"\"
%s
    \"\"\".trimIndent().lines()
}
""" % (
        digest,
        "\n".join("        " + "|".join("".join(map(str, r["scores"])) for r in results[start:start + 5]) for start in range(0, 465, 5)),
        "\n".join("        " + ";".join(["".join(map(str, answers)), result["type"], result["name"], ",".join(map(str, result["scores"])), str(result["neutral"])]) for answers, result in zip(mixed, results[465:])),
    )
    return text


def catalog(questions):
    def quoted(value):
        return json.dumps(value, ensure_ascii=False).replace("$", "\\$")
    rows = [f"        Mbti93Question({q[0]}, {quoted(q[1])}, {quoted(q[2])}, {quoted(q[3])}, MbtiLetter.{q[4]})," for q in questions]
    return "\n".join(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("html", type=Path, help="Local pinned mbti_test_93questions.htm")
    parser.add_argument("--write-fixtures", action="store_true", help="Regenerate the test oracle only")
    parser.add_argument("--print-catalog", action="store_true", help="Print source-derived Kotlin question entries")
    args = parser.parse_args()
    raw = args.html.read_bytes()
    assert hashlib.sha256(raw).hexdigest() == SOURCE_SHA256, "Source SHA-256 mismatch"
    source = raw.decode("utf-8")
    questions = extract(source)
    expected_fixture = fixtures(source, questions)
    fixture_path = ROOT / PACKAGE.format("commonTest") / "Mbti93SourceOracle.kt"
    if args.write_fixtures:
        fixture_path.parent.mkdir(parents=True, exist_ok=True)
        fixture_path.write_text(expected_fixture)
    else:
        assert fixture_path.read_text() == expected_fixture, "Source oracle differs from checked-in fixture"
    if args.print_catalog:
        print(catalog(questions))
    else:
        catalog_path = ROOT / PACKAGE.format("commonMain") / "Mbti93Catalog.kt"
        if catalog_path.exists() or not args.write_fixtures:
            actual = re.findall(r"^        Mbti93Question\(.*\),$", catalog_path.read_text(), re.M)
            assert "\n".join(actual) == catalog(questions), "Kotlin catalog differs from visible labels/source mapping"
        print(f"Verified pinned source {COMMIT}: 93 labels/mappings, 465 isolated answer scores, 40 complete cases")


if __name__ == "__main__":
    main()
