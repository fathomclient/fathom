# Regression thresholds

What Fathom is allowed to cost, and what happens when it costs more.

---

## 1. The number

**Fathom with every optional feature switched off, against Legacy Fabric alone,
in a CPU-bound scene:**

| Range | Verdict | What happens |
| --- | --- | --- |
| ≤ 3% | Within target | Carry on |
| 3–5% | Acceptable | Carry on, and put reducing it on the list |
| > 5% | **Above limit** | **Stop. No more Minecraft optimizations until it is understood.** |

That is configuration **D** against configuration **B** in the `cpu-ceiling`
scenario. Both halves matter:

- **D, not C.** C has the default modules on, so it measures the HUD as well.
  The overhead question is what Fathom costs when it is doing nothing.
- **B, not A.** A is vanilla with no loader. Comparing against it folds Legacy
  Fabric's cost into Fathom's, and the result is a number Fathom cannot act on.
- **`cpu-ceiling`, not a real scene.** A trivial scene removes the GPU from the
  answer. The same overhead measured in a forest could be either machine.

The threshold is encoded, not remembered: `bench::OVERHEAD_TARGET_PERCENT` and
`OVERHEAD_LIMIT_PERCENT`, with the verdict computed by `bench::Verdict::of` and
printed at the top of every summary.

---

## 2. Why stopping is the rule

Because the alternative is worse than it sounds.

A client that costs 8% before it has optimized anything, and then writes an
optimization worth 4%, is a client that is 4% slower than the loader it runs on
and can show a graph proving it improved. Every optimization after that is spent
paying off a debt nobody measured, and the debt compounds: each new hook adds to
the fixed cost every frame pays whether the feature is on or not.

The order matters more than the effort. **Find the overhead first**, because
every optimization written before it is understood is an optimization written
against the wrong baseline.

---

## 3. What to do when it is above the limit

In this order, because each step tells you whether the next is needed.

1. **Read the section table** in the summary. `Probe` splits Fathom's cost into
   HUD, modules, hooks, screen and other. One of them is the answer.
2. **Check `screen` is zero** with the Hub closed. If it is not, something is
   drawing that should not be, and that is the whole problem.
3. **Check `modules` against the widget count.** The HUD loop asks the module
   registry who owns each widget, per widget, per frame; the audit measured that
   as roughly 165 list probes a frame with everything off. If `modules`
   dominates, that lookup wants a cache built when the registry is sealed.
4. **Check the allocation rate** against configuration B. `FathomHud.render`
   allocates a `Window` per frame. Per-frame allocation does not show in mean
   frame time; it shows in GC pause count, and then in the 1% low.
5. **Only then look at the hooks.** There are three, two of them frame caps that
   run once per frame.

---

## 4. What is not a regression

- **A difference inside the run-to-run spread.** Three runs, median, spread
  reported. A 1% difference between single runs is noise.
- **A difference in a GPU-bound scene.** If the same configurations are equal at
  `cpu-ceiling` and differ in a forest, the forest measured the machine.
- **A difference with different settings.** The harness refuses these rather than
  reporting them, so this should not reach a human — but if a number appears
  without a settings check behind it, it is not a result.
- **A worse frame rate with more features on.** Configuration E and F are
  *supposed* to differ from D. The threshold is about D.

---

## 5. The other thresholds

Less load-bearing, and worth stating so they are not invented later.

| Measure | Target | Why |
| --- | --- | --- |
| Fathom's worst single frame, Hub closed | < 1 ms | Above that Fathom can cause a visible hitch on its own |
| `screen` section, Hub closed | 0.000 ms | Anything else means something is drawing while closed |
| Hub while open | < 2 ms/frame | It is an interface over a game; it may cost something, but not a frame |
| GC pause count, D against B | no increase | Per-frame allocation is the mechanism, and the 1% low is where it shows |

---

## 6. Status

**No measurement has been taken.** The harness exists, the probe exists, the
threshold is encoded and the summary computes the verdict — but no benchmark has
been run, so there is no number to compare against any of the above.

`docs/performance/current-state-audit.md` §8 has single Diagnostics readings from
a development session. They are not benchmarks and must not be quoted as any of
the numbers on this page.
