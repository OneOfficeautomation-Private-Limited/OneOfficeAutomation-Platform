"""Data packages: ooa.string, ooa.json, ooa.datatable."""
from __future__ import annotations

import csv
import io
import json
import re
from decimal import Decimal

from ..sdk import ActionException, Input, Output, action, package
from ..sdk.types import make_table, to_jsonable, to_number


@package("ooa.string", "String", "1.0.0", description="Text manipulation: extract, replace, split, change case.")
class StringPackage:
    @action("extract_text", "Extract text", category="Text",
            description="Return the text before, after or between markers.",
            inputs=[Input("text", "String", description="Source text."),
                    Input("before", "String", required=False, description="Return text before this marker."),
                    Input("after", "String", required=False, description="Return text after this marker."),
                    Input("trim", "Boolean", required=False, default=True, description="Trim whitespace from the result.")],
            outputs=[Output("result", "String", "Extracted text, empty when a marker is missing.")], idempotent=True)
    def extract_text(self, ctx, text, before=None, after=None, trim=True):
        out = text
        if after is not None:
            i = out.find(after)
            out = out[i + len(after):] if i >= 0 else ""
        if before is not None:
            i = out.find(before)
            out = out[:i] if i >= 0 else ("" if after is None else out)
        return {"result": out.strip() if trim else out}

    @action("replace", "Replace", category="Text", description="Replace text, optionally using a regular expression.",
            inputs=[Input("text", "String", description="Source text."),
                    Input("find", "String", description="Text or regex to find."),
                    Input("replace_with", "String", required=False, default="", description="Replacement text."),
                    Input("regex", "Boolean", required=False, default=False, description="Treat 'find' as a regular expression.")],
            outputs=[Output("result", "String", "Text after replacement.")], idempotent=True)
    def replace(self, ctx, text, find, replace_with="", regex=False):
        try:
            return {"result": re.sub(find, replace_with, text) if regex else text.replace(find, replace_with)}
        except re.error as e:
            raise ActionException(f"Invalid regular expression: {e}", code="STRING.BAD_REGEX",
                                  suggested_fix="Escape special characters such as ( ) [ ] . *")

    @action("split", "Split", category="Text", description="Split text into a list by a delimiter.",
            inputs=[Input("text", "String", description="Source text."),
                    Input("delimiter", "String", description="Delimiter, e.g. ','."),
                    Input("trim_items", "Boolean", required=False, default=True, description="Trim each item.")],
            outputs=[Output("items", "List", "List of parts.")], idempotent=True)
    def split(self, ctx, text, delimiter, trim_items=True):
        parts = text.split(delimiter)
        return {"items": [p.strip() for p in parts] if trim_items else parts}

    @action("change_case", "Change case", category="Text", description="Convert text to upper, lower, title or sentence case.",
            inputs=[Input("text", "String", description="Source text."),
                    Input("case", "String", enum=("upper", "lower", "title", "sentence"), description="Target case.")],
            outputs=[Output("result", "String", "Converted text.")], idempotent=True)
    def change_case(self, ctx, text, case):
        return {"result": {"upper": text.upper(), "lower": text.lower(), "title": text.title(),
                           "sentence": text[:1].upper() + text[1:].lower()}[case]}


def _get_path(obj, path: str):
    cur = obj
    for part in [p for p in re.split(r"\.|\[(\d+)\]", path) if p]:
        if isinstance(cur, list):
            try:
                cur = cur[int(part)]
            except (ValueError, IndexError):
                return None
        elif isinstance(cur, dict):
            cur = cur.get(part)
        else:
            return None
    return cur


@package("ooa.json", "JSON", "1.0.0", description="Parse, query and write JSON documents.")
class JsonPackage:
    @action("parse", "Parse JSON", category="JSON", description="Parse JSON text into a Dictionary or List.",
            inputs=[Input("text", "String", description="JSON text.")],
            outputs=[Output("value", "Any", "Parsed value.")], idempotent=True)
    def parse(self, ctx, text):
        try:
            return {"value": json.loads(text, parse_float=Decimal)}
        except json.JSONDecodeError as e:
            raise ActionException(f"Invalid JSON at line {e.lineno} column {e.colno}: {e.msg}", code="JSON.INVALID")

    @action("stringify", "Convert to JSON text", category="JSON", description="Serialise a value to JSON text.",
            inputs=[Input("value", "Any", description="Value to serialise."),
                    Input("indent", "Number", required=False, description="Indent spaces for pretty output.")],
            outputs=[Output("text", "String", "JSON text.")], idempotent=True)
    def stringify(self, ctx, value, indent=None):
        return {"text": json.dumps(to_jsonable(value), indent=int(indent) if indent else None)}

    @action("get_node", "Get node value", category="JSON", description="Read a value by dotted path, e.g. order.items[0].sku.",
            inputs=[Input("value", "Any", description="Dictionary or List to query."),
                    Input("path", "String", description="Dotted path with [n] indexes.")],
            outputs=[Output("result", "Any", "Value at the path, or empty.")], idempotent=True)
    def get_node(self, ctx, value, path):
        return {"result": _get_path(value, path)}

    @action("read_file", "Read JSON file", category="JSON", description="Read and parse a JSON file.",
            inputs=[Input("path", "File", description="Path to a .json file.")],
            outputs=[Output("value", "Any", "Parsed content.")], idempotent=True, capabilities=("filesystem",))
    def read_file(self, ctx, path):
        p = ctx.resolve_path(path)
        if not p.is_file():
            raise ActionException(f"File not found: {p}", code="FILE.NOT_FOUND", suggested_fix="Check the path.")
        return {"value": json.loads(p.read_text(encoding="utf-8"), parse_float=Decimal)}

    @action("write_file", "Write JSON file", category="JSON", description="Write a value to a JSON file (UTF-8, pretty-printed).",
            inputs=[Input("path", "File", description="Target path."), Input("value", "Any", description="Value to write.")],
            capabilities=("filesystem",))
    def write_file(self, ctx, path, value):
        p = ctx.resolve_path(path)
        p.parent.mkdir(parents=True, exist_ok=True)
        tmp = p.with_suffix(p.suffix + ".tmp")
        tmp.write_text(json.dumps(to_jsonable(value), indent=2), encoding="utf-8")
        tmp.replace(p)  # atomic write
        return {}


_OPS = {"eq": lambda a, b: a == b, "ne": lambda a, b: a != b, "gt": lambda a, b: a > b, "ge": lambda a, b: a >= b,
        "lt": lambda a, b: a < b, "le": lambda a, b: a <= b,
        "contains": lambda a, b: str(b).lower() in str(a).lower()}


def _cmp_value(v):
    try:
        return to_number(v)
    except Exception:
        return v


@package("ooa.datatable", "Data Table", "1.0.0", description="In-memory tables: build, filter, sort, read and write CSV.")
class DataTablePackage:
    @action("from_records", "Create from records", category="Table", description="Build a Table from a List of Dictionaries.",
            inputs=[Input("records", "List", description="List of Dictionaries with the same keys.")],
            outputs=[Output("table", "Table", "New table.")], idempotent=True)
    def from_records(self, ctx, records):
        cols = list(dict.fromkeys(k for r in records for k in r))
        return {"table": make_table(cols, [[r.get(c) for c in cols] for r in records])}

    @action("filter", "Filter rows", category="Table", description="Keep rows where a column satisfies a comparison.",
            inputs=[Input("table", "Table", description="Source table."),
                    Input("column", "String", description="Column name."),
                    Input("operator", "String", enum=tuple(_OPS), description="Comparison operator."),
                    Input("value", "Any", description="Value to compare against.")],
            outputs=[Output("table", "Table", "Filtered table."), Output("count", "Number", "Rows kept.")], idempotent=True)
    def filter(self, ctx, table, column, operator, value):
        if column not in table["columns"]:
            raise ActionException(f"Column '{column}' not found", code="TABLE.NO_COLUMN",
                                  suggested_fix=f"Columns: {', '.join(table['columns'])}.")
        i = table["columns"].index(column)
        rows = [r for r in table["rows"] if _OPS[operator](_cmp_value(r[i]), _cmp_value(value))]
        return {"table": make_table(table["columns"], rows), "count": len(rows)}

    @action("sort", "Sort", category="Table", description="Sort rows by a column.",
            inputs=[Input("table", "Table", description="Source table."), Input("column", "String", description="Column name."),
                    Input("descending", "Boolean", required=False, default=False, description="Sort descending.")],
            outputs=[Output("table", "Table", "Sorted table.")], idempotent=True)
    def sort(self, ctx, table, column, descending=False):
        i = table["columns"].index(column)
        rows = sorted(table["rows"], key=lambda r: (r[i] is None, _cmp_value(r[i])), reverse=descending)
        return {"table": make_table(table["columns"], rows)}

    @action("read_csv", "Read CSV", category="CSV", description="Read a CSV file into a Table (first row = headers).",
            inputs=[Input("path", "File", description="CSV path."),
                    Input("delimiter", "String", required=False, default=",", description="Field delimiter.")],
            outputs=[Output("table", "Table", "Table of string values.")], idempotent=True, capabilities=("filesystem",))
    def read_csv(self, ctx, path, delimiter=","):
        with open(ctx.resolve_path(path), newline="", encoding="utf-8-sig") as fh:
            rows = list(csv.reader(fh, delimiter=delimiter))
        return {"table": make_table(rows[0] if rows else [], rows[1:])}

    @action("write_csv", "Write CSV", category="CSV", description="Write a Table to a CSV file with a header row.",
            inputs=[Input("table", "Table", description="Table to write."), Input("path", "File", description="Target path.")],
            capabilities=("filesystem",))
    def write_csv(self, ctx, table, path):
        p = ctx.resolve_path(path)
        p.parent.mkdir(parents=True, exist_ok=True)
        buf = io.StringIO()
        w = csv.writer(buf)
        w.writerow(table["columns"])
        w.writerows([[to_jsonable(c) for c in r] for r in table["rows"]])
        p.write_text(buf.getvalue(), encoding="utf-8")
        return {}
