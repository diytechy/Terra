#!/usr/bin/env python3
"""
tesf_size_check.py

Scans TerraScript (.tesf) files in a Terra pack directory and warns if any
structure may place blocks more than THRESHOLD blocks from its origin in the
horizontal (X or Z) plane.  Only X and Z are checked; Y is fully loaded
during population and is never truncated.

Bukkit's LimitedRegion covers the generating chunk plus one neighboring chunk
width (16 blocks) in each direction.  Blocks outside that region are silently
discarded per-block -- the structure itself is not rejected, it is simply
trimmed at the boundary.  A structure whose origin lands at a chunk corner
therefore has exactly 16 blocks of runway before truncation begins, making 16
the correct conservative threshold for the worst-case placement position.

Uses interval (range) arithmetic to propagate variable bounds through
expressions, for loops, and common built-in functions.  Results are grouped
into three categories:

  WARNING          - the bound certainly exceeds the threshold
  POSSIBLE WARNING - the bound exceeds the threshold but relies on uncertain
                     values (randomInt, trig with variable argument, etc.)
  UNCERTAIN        - within threshold but at least one coordinate is uncertain;
                     manual review recommended

Usage:
    python tesf_size_check.py [pack_directory] [--threshold N]

Examples:
    python tesf_size_check.py C:/Projects/ORIGEN2
    python tesf_size_check.py C:/Projects/ORIGEN2 --threshold 24
"""

import math
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

DEFAULT_THRESHOLD = 16  # blocks from origin; worst case is structure origin at chunk corner,
                        # giving exactly 16 blocks into the neighboring chunk before truncation

# ── Color support ─────────────────────────────────────────────────────────────
# Enable VT processing on Windows so ANSI codes work in cmd / PowerShell.
if sys.platform == "win32":
    try:
        import ctypes
        kernel32 = ctypes.windll.kernel32
        kernel32.SetConsoleMode(kernel32.GetStdHandle(-11), 7)
    except Exception:
        pass

_USE_COLOR = sys.stdout.isatty()
_YEL  = "\033[93m" if _USE_COLOR else ""   # bright yellow
_RST  = "\033[0m"  if _USE_COLOR else ""   # reset

def _warn(text: str) -> str:
    """Wrap text in yellow for warning labels."""
    return f"{_YEL}{text}{_RST}"


# ──────────────────────────────────────────────────────────────────────────────
# Interval arithmetic
# ──────────────────────────────────────────────────────────────────────────────

@dataclass
class Iv:
    """Closed interval [lo, hi].  certain=False means the bounds are estimates."""
    lo: float
    hi: float
    certain: bool = True

    @classmethod
    def const(cls, v: float) -> "Iv":
        return cls(float(v), float(v))

    @classmethod
    def unk(cls) -> "Iv":
        """Completely unknown value."""
        return cls(-1e9, 1e9, False)

    def max_abs(self) -> float:
        return max(abs(self.lo), abs(self.hi))

    def union(self, other: "Iv") -> "Iv":
        return Iv(min(self.lo, other.lo), max(self.hi, other.hi),
                  self.certain and other.certain)

    def __add__(self, other: "Iv") -> "Iv":
        return Iv(self.lo + other.lo, self.hi + other.hi,
                  self.certain and other.certain)

    def __sub__(self, other: "Iv") -> "Iv":
        return Iv(self.lo - other.hi, self.hi - other.lo,
                  self.certain and other.certain)

    def __mul__(self, other: "Iv") -> "Iv":
        p = [self.lo*other.lo, self.lo*other.hi, self.hi*other.lo, self.hi*other.hi]
        return Iv(min(p), max(p), self.certain and other.certain)

    def __truediv__(self, other: "Iv") -> "Iv":
        if other.lo <= 0 <= other.hi:
            return Iv.unk()
        p = [self.lo/other.lo, self.lo/other.hi, self.hi/other.lo, self.hi/other.hi]
        return Iv(min(p), max(p), self.certain and other.certain)

    def __neg__(self) -> "Iv":
        return Iv(-self.hi, -self.lo, self.certain)

    def __repr__(self) -> str:
        pfx = "" if self.certain else "~"
        if self.lo == self.hi:
            return f"{pfx}{self.lo:g}"
        return f"{pfx}[{self.lo:g}, {self.hi:g}]"


def _trig_range(fn: str, lo: float, hi: float) -> tuple[float, float]:
    """Tight [min, max] for sin or cos over the interval [lo, hi] (radians)."""
    if hi - lo >= 2 * math.pi:
        return -1.0, 1.0
    f = math.sin if fn == "sin" else math.cos
    # Extrema of sin at π/2 + kπ; extrema of cos at kπ
    period_offset = math.pi / 2 if fn == "sin" else 0.0
    n_lo = math.ceil((lo - period_offset) / math.pi)
    n_hi = math.floor((hi - period_offset) / math.pi)
    samples = [f(lo), f(hi)]
    for n in range(int(n_lo), int(n_hi) + 1):
        samples.append(f(n * math.pi + period_offset))
    return min(samples), max(samples)


# ──────────────────────────────────────────────────────────────────────────────
# Tokenizer
# ──────────────────────────────────────────────────────────────────────────────

_TOK = re.compile(
    r"(\d+\.\d+|\.\d+|\d+)"   # number
    r"|([A-Za-z_]\w*)"         # identifier / keyword
    r"|([+\-*/%])"             # operator
    r"|([(),])"                # delimiters
    r"|\s+"                    # whitespace (skipped)
)

def _tokenize(expr: str) -> list[tuple[str, object]]:
    tokens: list[tuple[str, object]] = []
    for m in _TOK.finditer(expr):
        num, ident, op, delim = m.groups()
        if num:
            tokens.append(("N", float(num)))
        elif ident:
            tokens.append(("I", ident))
        elif op:
            tokens.append(("O", op))
        elif delim:
            tokens.append(("D", delim))
    return tokens


# ──────────────────────────────────────────────────────────────────────────────
# Recursive-descent expression evaluator -> Iv
# ──────────────────────────────────────────────────────────────────────────────

class _Eval:
    def __init__(self, tokens: list, env: dict[str, Iv]):
        self.t = tokens
        self.i = 0
        self.env = env

    def peek(self) -> Optional[tuple]:
        return self.t[self.i] if self.i < len(self.t) else None

    def eat(self) -> tuple:
        v = self.t[self.i]; self.i += 1; return v

    def parse(self) -> Iv:
        return self._add()

    def _add(self) -> Iv:
        v = self._mul()
        while self.peek() and self.peek()[0] == "O" and self.peek()[1] in "+-":
            op = self.eat()[1]
            r = self._mul()
            v = v + r if op == "+" else v - r
        return v

    def _mul(self) -> Iv:
        v = self._unary()
        while self.peek() and self.peek()[0] == "O" and self.peek()[1] in "*/%":
            op = self.eat()[1]
            r = self._unary()
            if op == "*":
                v = v * r
            elif op == "/":
                v = v / r
            else:  # %
                hi = max(abs(r.lo), abs(r.hi))
                v = Iv(0.0, hi, v.certain and r.certain)
        return v

    def _unary(self) -> Iv:
        if self.peek() == ("O", "-"):
            self.eat()
            return -self._primary()
        return self._primary()

    def _primary(self) -> Iv:
        t = self.peek()
        if t is None:
            return Iv.unk()
        if t[0] == "N":
            self.eat()
            return Iv.const(t[1])
        if t[0] == "I":
            name = t[1]; self.eat()
            if self.peek() == ("D", "("):
                return self._call(name)
            return self.env.get(name, Iv.unk())
        if t == ("D", "("):
            self.eat()
            v = self.parse()
            if self.peek() == ("D", ")"):
                self.eat()
            return v
        return Iv.unk()

    def _args(self) -> list[Iv]:
        args: list[Iv] = []
        self.eat()  # consume "("
        if self.peek() == ("D", ")"):
            self.eat(); return args
        args.append(self.parse())
        while self.peek() == ("D", ","):
            self.eat()
            args.append(self.parse())
        if self.peek() == ("D", ")"):
            self.eat()
        return args

    def _call(self, name: str) -> Iv:
        args = self._args()
        fn = name.lower()

        if fn == "randomint":
            n = args[0].hi if args else 1.0
            return Iv(0.0, max(0.0, n - 1), False)

        if fn in ("sin", "cos"):
            if args:
                v = args[0]
                if v.certain:
                    lo, hi = _trig_range(fn, v.lo, v.hi)
                    return Iv(lo, hi, True)
            return Iv(-1.0, 1.0, False)

        if fn == "tan":
            return Iv(-1e9, 1e9, False)  # unbounded

        if fn == "abs" and args:
            v = args[0]
            return Iv(0.0, max(abs(v.lo), abs(v.hi)), v.certain)

        if fn == "sqrt" and args:
            v = args[0]
            lo = math.sqrt(max(0.0, v.lo))
            hi = math.sqrt(max(0.0, v.hi))
            return Iv(lo, hi, v.certain)

        if fn in ("pow", "pow2") and args:
            if fn == "pow2":
                # pow2(x) = x²
                v = args[0]
            else:
                v = args[0]
                exp = args[1] if len(args) > 1 else Iv.const(1)
                if exp.lo == exp.hi == 2.0:
                    pass  # fall through to x² logic
                else:
                    return Iv.unk()
            lo = min(v.lo**2, v.hi**2)
            hi = max(v.lo**2, v.hi**2)
            if v.lo <= 0 <= v.hi:
                lo = 0.0
            return Iv(lo, hi, v.certain)

        if fn in ("floor", "ceil") and args:
            v = args[0]
            return Iv(math.floor(v.lo), math.ceil(v.hi), v.certain)

        if fn == "max" and len(args) >= 2:
            c = all(a.certain for a in args)
            return Iv(max(args[0].lo, args[1].lo), max(args[0].hi, args[1].hi), c)

        if fn == "min" and len(args) >= 2:
            c = all(a.certain for a in args)
            return Iv(min(args[0].lo, args[1].lo), min(args[0].hi, args[1].hi), c)

        # Noise samplers always return [-1, 1]
        if fn == "sampler":
            return Iv(-1.0, 1.0, False)

        # World-coordinate accessors — uncertain but treated as zero-offset
        # for bounding purposes (they appear as sampler inputs, not block offsets)
        if fn in ("originx", "originy", "originz"):
            return Iv.unk()

        # check(), getBlock() — string-returning predicates, not numeric
        return Iv.unk()


def eval_expr(expr: str, env: dict[str, Iv]) -> Iv:
    try:
        tokens = _tokenize(expr)
        if not tokens:
            return Iv.unk()
        return _Eval(tokens, env).parse()
    except Exception:
        return Iv.unk()


# ──────────────────────────────────────────────────────────────────────────────
# Argument splitter (respects nested parentheses)
# ──────────────────────────────────────────────────────────────────────────────

def _split_args(s: str) -> list[str]:
    args, cur, depth = [], [], 0
    for ch in s:
        if ch == "(":
            depth += 1; cur.append(ch)
        elif ch == ")":
            depth -= 1; cur.append(ch)
        elif ch == "," and depth == 0:
            args.append("".join(cur).strip()); cur = []
        else:
            cur.append(ch)
    if cur:
        args.append("".join(cur).strip())
    return args


def _find_calls(source: str, fn: str) -> list[list[str]]:
    """Return argument lists for every call to fn(…) in source."""
    results = []
    for m in re.finditer(r"\b" + re.escape(fn) + r"\s*\(", source):
        start = m.end()
        depth, i = 1, start
        while i < len(source) and depth:
            if source[i] == "(":
                depth += 1
            elif source[i] == ")":
                depth -= 1
            i += 1
        results.append(_split_args(source[start : i - 1]))
    return results


# ──────────────────────────────────────────────────────────────────────────────
# TESF environment builder
# ──────────────────────────────────────────────────────────────────────────────

# num varname = expr;
_NUM_DECL = re.compile(r"\bnum\s+(\w+)\s*=\s*([^;{}\n]+);")

# for(num var = lo; var OP hi; ...)  — captures the comparison operator too
_FOR_LOOP = re.compile(
    r"\bfor\s*\(\s*num\s+(\w+)\s*=\s*([^;]+);\s*\w+\s*(<|<=|>|>=)\s*([^;]+);"
)


def _build_env(source: str) -> dict[str, Iv]:
    """
    Build a variable-to-Interval map by processing all num declarations in
    source order, then overlay for-loop variable ranges.
    """
    env: dict[str, Iv] = {}

    # Process num declarations in the order they appear
    for m in _NUM_DECL.finditer(source):
        name = m.group(1)
        expr = m.group(2).strip()
        # Only evaluate if not already set by a prior (earlier) declaration;
        # re-assignments are unioned so we cover all values the var could hold.
        new_val = eval_expr(expr, env)
        if name in env:
            env[name] = env[name].union(new_val)
        else:
            env[name] = new_val

    # Overlay for-loop variable ranges
    for m in _FOR_LOOP.finditer(source):
        var = m.group(1)
        lo_expr = m.group(2).strip()
        op = m.group(3)
        hi_expr = m.group(4).strip()

        lo_iv = eval_expr(lo_expr, env)
        hi_iv = eval_expr(hi_expr, env)

        # Adjust for strict inequality (<, >)
        if op == "<":
            hi_iv = Iv(hi_iv.lo - 1, hi_iv.hi - 1, hi_iv.certain)
        elif op == ">":
            lo_iv = Iv(lo_iv.lo + 1, lo_iv.hi + 1, lo_iv.certain)

        # The loop variable spans [lo, hi] regardless of direction
        loop_iv = Iv(
            min(lo_iv.lo, hi_iv.lo),
            max(lo_iv.hi, hi_iv.hi),
            lo_iv.certain and hi_iv.certain,
        )

        if var in env:
            env[var] = env[var].union(loop_iv)
        else:
            env[var] = loop_iv

    return env


# ──────────────────────────────────────────────────────────────────────────────
# Per-file analysis
# ──────────────────────────────────────────────────────────────────────────────

def _analyze(path: Path) -> dict:
    source = re.sub(r"//[^\n]*", "", path.read_text(encoding="utf-8", errors="replace"))
    env = _build_env(source)

    x_iv: Optional[Iv] = None
    z_iv: Optional[Iv] = None
    n_blocks = 0
    n_structs = 0

    def _accumulate(args: list[str]) -> None:
        nonlocal x_iv, z_iv
        xv = eval_expr(args[0], env)
        zv = eval_expr(args[2], env)
        x_iv = xv if x_iv is None else x_iv.union(xv)
        z_iv = zv if z_iv is None else z_iv.union(zv)

    for args in _find_calls(source, "block"):
        if len(args) >= 3:
            n_blocks += 1
            _accumulate(args)

    for args in _find_calls(source, "structure"):
        if len(args) >= 3:
            n_structs += 1
            _accumulate(args)
            # Note: we only capture the call-site offset, not the
            # sub-structure's own footprint.

    return {
        "x": x_iv, "z": z_iv,
        "blocks": n_blocks, "structs": n_structs,
    }


# ──────────────────────────────────────────────────────────────────────────────
# Reporter
# ──────────────────────────────────────────────────────────────────────────────

def check_pack(pack_dir: Path, threshold: int = DEFAULT_THRESHOLD,
               show_all: bool = False) -> None:
    tesf_files = sorted(pack_dir.rglob("*.tesf"))
    if not tesf_files:
        print(f"No .tesf files found under {pack_dir}")
        return

    grid = threshold * 2 + 1
    print(f"Scanning {len(tesf_files)} .tesf file(s) in: {pack_dir}")
    print(f"Threshold : +-{threshold} blocks from origin  ({grid}x{grid} footprint)\n")

    warns: list[tuple[str, str, str]] = []   # (rel, level, detail)
    uncerts: list[tuple[str, str]] = []      # (rel, detail)
    oks: list[tuple[str, str]] = []          # (rel, detail)
    empties: list[str] = []

    for path in tesf_files:
        rel = str(path.relative_to(pack_dir))
        r = _analyze(path)
        x, z = r["x"], r["z"]

        if x is None and z is None:
            empties.append(rel)
            continue

        x_abs = x.max_abs() if x else 0.0
        z_abs = z.max_abs() if z else 0.0
        exceeds = x_abs > threshold or z_abs > threshold
        certain = (x.certain if x else True) and (z.certain if z else True)

        parts = []
        if x:
            parts.append(f"X={x}  (max|x|={x_abs:.1f})")
        if z:
            parts.append(f"Z={z}  (max|z|={z_abs:.1f})")
        detail = "  |  ".join(parts)

        has_structs = r["structs"] > 0
        struct_note = "  [has structure() sub-calls - footprint may be larger]" if has_structs else ""

        if exceeds:
            level = "WARNING" if certain else "POSSIBLE WARNING"
            warns.append((rel, level, detail + struct_note))
        elif not certain:
            uncerts.append((rel, detail + struct_note))
        else:
            oks.append((rel, detail))

    SEP = "-" * 76

    if warns:
        print(SEP)
        print(f"  {_warn(f'EXCEEDS {grid}x{grid}')}  ({len(warns)} file(s))")
        print(SEP)
        for rel, level, detail in warns:
            print(f"  {_warn(f'[{level}]')}")
            print(f"    {rel}")
            print(f"    {detail}")
            print()

    if uncerts:
        print(SEP)
        print(f"  {_warn('UNCERTAIN')} - dynamic coords, manual review recommended  ({len(uncerts)} file(s))")
        print(SEP)
        for rel, detail in uncerts:
            print(f"  {_warn('[?]')} {rel}")
            print(f"      {detail}")
            print()

    if show_all:
        if oks:
            print(SEP)
            print(f"  OK - within {grid}x{grid}  ({len(oks)} file(s))")
            print(SEP)
            for rel, detail in oks:
                print(f"  [ok] {rel}")
                print(f"       {detail}")
                print()

        if empties:
            print(SEP)
            print(f"  NO BLOCK/STRUCTURE CALLS  ({len(empties)} file(s))")
            print(SEP)
            for rel in empties:
                print(f"       {rel}")
            print()

    print(SEP)
    print(f"  {_warn(f'{len(warns)} warning(s)')}  |  {_warn(f'{len(uncerts)} uncertain')}  |  {len(oks)} OK  |  {len(empties)} empty")
    if not show_all:
        print(f"  (run with --show-all to include OK and empty files)")
    print(SEP)


# ──────────────────────────────────────────────────────────────────────────────
# Entry point
# ──────────────────────────────────────────────────────────────────────────────

def main() -> None:
    args = sys.argv[1:]
    threshold = DEFAULT_THRESHOLD
    show_all = False
    positional: list[str] = []

    i = 0
    while i < len(args):
        if args[i] in ("--threshold", "-t") and i + 1 < len(args):
            try:
                threshold = int(args[i + 1])
            except ValueError:
                print(f"Error: --threshold requires an integer, got {args[i+1]!r}")
                sys.exit(1)
            i += 2
        elif args[i] in ("--show-all", "-a"):
            show_all = True
            i += 1
        else:
            positional.append(args[i])
            i += 1

    if positional:
        pack_dir = Path(positional[0])
    else:
        raw = input("Pack directory: ").strip().strip('"').strip("'")
        pack_dir = Path(raw)

    if not pack_dir.is_dir():
        print(f"Error: {pack_dir!r} is not a directory", file=sys.stderr)
        sys.exit(1)

    check_pack(pack_dir, threshold, show_all)


if __name__ == "__main__":
    main()
