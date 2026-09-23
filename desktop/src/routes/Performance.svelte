<!--
  Performance.

  A top-level destination, not a tab inside Settings. Structure:

      segmented preset ── the choice most people make once and never revisit
      module status    ── whether the game-side half is actually loaded
      categories       ── the detail, collapsed, for people who want it

  WHAT CHANGED

  The presets were four large boxes of prose, which is the wrong kind of dense:
  a lot of reading for one decision. They are now a segmented control with a
  sliding indicator, and the chosen preset's sentence appears once beneath it.
  Four descriptions become one.

  Every row still carries what it actually is. A switch that is on for an
  optimization the module does not implement says so, in the row, next to the
  switch. A performance screen where twenty things are toggleable and none of
  them run is the most common lie in this category of software, and it is told
  by omission.
-->
<script lang="ts">
  import { onMount } from 'svelte';
  import { fade, fly } from 'svelte/transition';
  import { Gauge, CircleCheck, CircleAlert, Zap } from '@lucide/svelte';

  import Page from '../lib/ui/Page.svelte';
  import Section from '../lib/ui/Section.svelte';
  import Row from '../lib/ui/Row.svelte';
  import Switch from '../lib/ui/Switch.svelte';
  import Segmented from '../lib/ui/Segmented.svelte';
  import { EASE, FAST, NORMAL, STAGGER } from '../lib/motion';
  import {
    performanceView,
    setOptimization,
    setPreset,
    unlockFrameRate,
    type PerformanceView,
  } from '../lib/api';

  export let onChanged: () => void = () => {};

  let view: PerformanceView | null = null;
  let busy = false;
  /**
   * Which categories are expanded.
   *
   * Seeded explicitly to `false` for every group rather than left empty. A
   * `bind:` whose value is `undefined` falls back to the child's default, and
   * `Section` defaults to open — so an empty map renders every category
   * expanded, which is the flat wall of switches this page is arranged to
   * avoid.
   */
  let expanded: Record<string, boolean> = {};

  onMount(() => {
    void (async () => {
      const loaded = await performanceView();
      expanded = Object.fromEntries(loaded.groups.map((group) => [group.id, false]));
      view = loaded;
    })();
  });

  /**
   * Raise the frame limit and turn VSync off for this profile.
   *
   * Only reachable from the notice, which only appears when the profile is
   * actually limited. Nothing here happens on its own: options.txt belongs to
   * whoever has been playing, and somebody who capped their frame rate on
   * purpose should keep it until they say otherwise.
   */
  async function unlockFrames() {
    if (busy) return;
    busy = true;
    try {
      view = await unlockFrameRate();
      onChanged();
    } finally {
      busy = false;
    }
  }

  async function choosePreset(id: string) {
    if (busy) return;
    busy = true;
    try {
      view = await setPreset(id);
      onChanged();
    } finally {
      busy = false;
    }
  }

  async function toggle(id: string, enabled: boolean) {
    if (busy) return;
    busy = true;
    try {
      // The whole refreshed view comes back, so the page cannot show a switch
      // as on after the core declined to turn it on.
      view = await setOptimization(id, enabled);
      onChanged();
    } finally {
      busy = false;
    }
  }

  $: selectedPreset = view?.presets.find((preset) => preset.selected);
</script>

{#if view}
  <Page title="Performance" summary="{view.profileName} · {view.summary}">
    <!-- FRAME LIMIT NOTICE -->
    {#if view.videoLimits}
      <!-- Shown only for a profile whose own saved video settings hold the
           frame rate down. Minecraft 1.8.9 ships with maxFps 120 and VSync on,
           so every profile created before Fathom seeded its own defaults has
           them, and no amount of optimization work is visible through a 120 fps
           limiter. -->
      <div class="notice" transition:fade={{ duration: FAST, easing: EASE }}>
        <CircleAlert size={18} />
        <div class="notice-text">
          <strong>{view.videoLimits.summary}</strong>
          <span>
            These are Minecraft's own defaults, not a Fathom setting. Measured on this
            machine, lifting them took a test scene from 120 to 384 fps. Your other video
            settings are left alone.
          </span>
        </div>
        <button class="unlock" disabled={busy} on:click={unlockFrames}>
          Remove the limit
        </button>
      </div>
    {/if}

    <!-- PRESET -->
    <Section label="Preset">
      <div class="preset">
        <Segmented
          options={view.presets.map((preset) => ({ id: preset.id, label: preset.label }))}
          value={selectedPreset?.id ?? 'custom'}
          disabled={busy}
          onChange={choosePreset}
        />

        {#if selectedPreset}
          <!-- One description, for the chosen preset. Four descriptions on
               screen at once is four times the reading for one decision. -->
          {#key selectedPreset.id}
            <p class="preset-detail" in:fade={{ duration: FAST, easing: EASE }}>
              {selectedPreset.description}
            </p>
          {/key}
        {/if}
      </div>
    </Section>

    <!-- MODULE -->
    <Section label="Game module" material>
      <Row
        title={view.module.headline}
        detail={view.module.detail}
        badge={view.module.installed ? view.module.version : 'Not loaded'}
        tone={view.module.installed ? 'good' : 'warn'}
      >
        <span slot="icon" class="module-icon" class:on={view.module.installed}>
          {#if view.module.installed}
            <CircleCheck size={17} strokeWidth={2} />
          {:else}
            <CircleAlert size={17} strokeWidth={2} />
          {/if}
        </span>
      </Row>
    </Section>

    <!-- MEASUREMENTS -->
    <!--
      What was actually measured, on this machine, in this profile.

      Nothing here is a comparison, because there is nothing to compare against:
      one run has no baseline, and a percentage built from one run is the
      "+42% FPS" claim the licensing research specifically forbids. So the page
      shows the numbers the module recorded, the module's own caveats about
      them, and no adjective at all.

      With no run recorded the section says how to make one and claims nothing.
      That is the honest state and it is also the common one.
    -->
    <Section
      label="Measured"
      summary={view.benchmarkCount > 1 ? `newest of ${view.benchmarkCount} runs` : ''}
    >
      {#if view.benchmark}
        <Row
          title={view.benchmark.headline}
          detail="Recorded in game on this machine. Not a comparison against anything."
          badge={view.benchmark.partial ? 'Partial' : view.benchmark.label}
          tone={view.benchmark.partial ? 'warn' : 'good'}
          expandable
        >
          <span slot="icon"><Gauge size={17} strokeWidth={1.9} /></span>
          <svelte:fragment slot="details">
            <div class="metrics">
              <div class="metric-group">
                <h4>Frames</h4>
                {#each view.benchmark.frames as metric}
                  <div class="metric">
                    <span>{metric.label}</span><span class="tnum">{metric.value}</span>
                  </div>
                {/each}
              </div>
              <div class="metric-group">
                <h4>Memory</h4>
                {#each view.benchmark.memory as metric}
                  <div class="metric">
                    <span>{metric.label}</span><span class="tnum">{metric.value}</span>
                  </div>
                {/each}
              </div>
              <div class="metric-group">
                <h4>Garbage collection</h4>
                {#each view.benchmark.gc as metric}
                  <div class="metric">
                    <span>{metric.label}</span><span class="tnum">{metric.value}</span>
                  </div>
                {/each}
              </div>
            </div>
            {#if view.benchmark.caveats.length}
              <ul class="caveats">
                {#each view.benchmark.caveats as caveat}
                  <li>{caveat}</li>
                {/each}
              </ul>
            {/if}
          </svelte:fragment>
        </Row>
      {:else}
        <p class="unmeasured">
          Nothing measured yet. Press Right Shift in game, open Diagnostics, and start a
          benchmark; the result appears here.
        </p>
      {/if}
    </Section>

    <!-- CATEGORIES -->
    <div class="groups">
      {#each view.groups as group}
        <Section
          label={group.label}
          summary={group.summary}
          collapsible
          bind:open={expanded[group.id]}
        >
          {#each group.rows as row, index}
            <div in:fly={{ y: 6, duration: NORMAL, delay: index * STAGGER, easing: EASE }}>
              <Row
                title={row.name}
                detail={row.summary}
                badge={row.running ? row.category : row.status}
                tone={row.running ? row.tone : 'neutral'}
                expandable
                dimmed={!row.running}
                compact
              >
                <svelte:fragment slot="control">
                  <!--
                    No switch on something that is not implemented.

                    It used to have one, greyed by nothing, and flipping it did
                    exactly nothing: presets refuse to enable an unimplemented
                    entry and the config bridge writes `false` for one whatever
                    the profile says. A control that moves and changes nothing is
                    worse than no control, because it teaches the user that the
                    switches on this page are decorative.

                    The row still says what the entry is and what state it is in.
                    That is the honest surface for work that is described and not
                    written.
                  -->
                  {#if row.canToggle}
                    {#if row.running && row.enabled}
                      <span class="live" title="Running now" transition:fade={{ duration: FAST }}>
                        <Zap size={11} strokeWidth={2.6} />
                      </span>
                    {/if}
                    <Switch
                      checked={row.enabled}
                      disabled={busy}
                      label={row.name}
                      onChange={(value) => toggle(row.id, value)}
                    />
                  {/if}
                </svelte:fragment>

                <svelte:fragment slot="details">
                  <p class="d">{row.detail}</p>
                  <dl>
                    <div><dt>Classification</dt><dd>{row.category}</dd></div>
                    <div><dt>Implementation</dt><dd>{row.readiness}</dd></div>
                    <div>
                      <dt>Measured</dt>
                      <!-- Never invented. A row with no evidence says so
                           rather than borrowing a number from elsewhere. -->
                      <dd>{row.evidence ?? 'Not benchmarked yet.'}</dd>
                    </div>
                  </dl>
                </svelte:fragment>
              </Row>
            </div>
          {/each}
        </Section>
      {/each}
    </div>
  </Page>
{/if}

<style>
  /* Deliberately not a Row: this is a one-off condition with an action, not a
     setting, and it should stop reading like one the moment it is dealt with.
     It removes itself when `videoLimits` comes back null. */
  .notice {
    display: flex;
    align-items: flex-start;
    gap: var(--s-3);
    padding: var(--s-3);
    margin-bottom: var(--s-4);
    border: 1px solid color-mix(in srgb, var(--warn) 40%, transparent);
    border-radius: var(--r-2, 8px);
    background: color-mix(in srgb, var(--warn) 8%, transparent);
    color: var(--warn);
  }

  .notice-text {
    display: flex;
    flex-direction: column;
    gap: 2px;
    flex: 1;
    min-width: 0;
  }

  .notice-text strong {
    font-size: var(--t-body);
    color: var(--text);
  }

  .notice-text span {
    font-size: var(--t-body);
    color: var(--text-muted);
    line-height: 1.5;
  }

  .unlock {
    flex-shrink: 0;
    padding: 6px 12px;
    border: 1px solid var(--chrome-line);
    border-radius: var(--r-1, 6px);
    background: var(--surface, transparent);
    color: var(--text);
    font-size: var(--t-body);
    cursor: pointer;
  }

  .unlock:hover:not(:disabled) {
    border-color: var(--warn);
  }

  .unlock:disabled {
    opacity: 0.5;
    cursor: default;
  }

  .preset {
    display: flex;
    flex-direction: column;
    gap: var(--s-3);
  }

  .preset-detail {
    margin: 0;
    max-width: 560px;
    font-size: var(--t-body);
    color: var(--text-muted);
    line-height: 1.5;
  }

  /* Tighter than the page's default section spacing, and with a hairline
     between each: six collapsed categories 22px apart with nothing between
     them read as loose labels rather than as a list. */
  .groups {
    display: flex;
    flex-direction: column;
    gap: 0;
  }

  .groups :global(section) {
    padding: 7px 0;
    box-shadow: 0 -1px 0 var(--chrome-line);
  }

  .groups :global(section:first-child) {
    box-shadow: none;
  }

  /* The label row is the whole control when collapsed, so it gets the padding
     that makes it feel like a row rather than a caption. */
  .groups :global(.head) {
    padding: 4px 2px;
  }

  /* Three columns of label/value pairs. A table would align the numbers
     across groups, which is exactly wrong: a frame time and a heap size are
     not comparable and should not read as a column of one thing. */
  .metrics {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
    gap: var(--s-4);
  }

  .metric-group h4 {
    margin: 0 0 var(--s-2);
    font-size: var(--t-meta);
    text-transform: uppercase;
    letter-spacing: var(--track-wide);
    color: var(--text-faint);
    font-weight: var(--w-medium);
  }

  .metric {
    display: flex;
    justify-content: space-between;
    gap: var(--s-3);
    padding: 2px 0;
    font-size: var(--t-body);
    color: var(--text-muted);
  }

  .metric span:last-child {
    color: var(--text);
  }

  .caveats {
    margin: var(--s-3) 0 0;
    padding-left: 18px;
    font-size: var(--t-caption);
    color: var(--text-faint);
    line-height: 1.5;
  }

  .unmeasured {
    margin: 0;
    padding: 14px;
    font-size: var(--t-body);
    color: var(--text-faint);
    line-height: 1.5;
  }

  .module-icon {
    color: var(--warn);
  }

  .module-icon.on {
    color: var(--good);
  }

  .live {
    display: grid;
    place-items: center;
    width: 20px;
    height: 20px;
    border-radius: var(--r-pill);
    color: var(--good);
    background: rgba(79, 201, 138, 0.15);
  }

  .d {
    margin: 0 0 9px;
  }

  dl {
    display: flex;
    flex-direction: column;
    gap: var(--s-1);
    margin: 0;
    padding-top: var(--s-2);
    border-top: 1px solid var(--chrome-line);
  }

  dl div {
    display: flex;
    gap: var(--s-3);
  }

  dt {
    flex-shrink: 0;
    width: 110px;
    color: var(--text-faint);
    font-size: var(--t-meta);
    text-transform: uppercase;
    letter-spacing: var(--track-wide);
    padding-top: var(--s-1);
  }

  dd {
    margin: 0;
    flex: 1;
  }
</style>
