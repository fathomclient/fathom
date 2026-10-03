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
  import { ICON } from '../lib/metrics.generated';
  import { onMount } from 'svelte';
  import { fade, fly } from 'svelte/transition';
  import { Gauge, CircleCheck, CircleAlert } from '@lucide/svelte';

  import Page from '../lib/ui/Page.svelte';
  import Section from '../lib/ui/Section.svelte';
  import Row from '../lib/ui/Row.svelte';
  import Switch from '../lib/ui/Switch.svelte';
  import Segmented from '../lib/ui/Segmented.svelte';
  import Notice from '../lib/ui/Notice.svelte';
  import Button from '../lib/ui/Button.svelte';
  import EmptyState from '../lib/ui/EmptyState.svelte';
  import { tooltip } from '../lib/tooltip';
  import { EASE, FAST, NORMAL, STAGGER } from '../lib/motion';
  import {
    performanceView,
    setOptimization,
    setPreset,
    setRenderRegions,
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

  /**
   * OptiFine's Render Regions, on or off. Its setting, not ours: only ever
   * changed from this switch, and it takes effect next launch.
   */
  async function chooseRenderRegions(on: boolean) {
    if (busy) return;
    busy = true;
    try {
      view = await setRenderRegions(on);
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
      <div transition:fade={{ duration: FAST, easing: EASE }}>
        <Notice tone="warning">
          <div class="notice-text">
            <strong>{view.videoLimits.summary}</strong>
            <span>
              These are Minecraft's own defaults, not a Fathom setting. Measured on this
              machine, lifting them took a test scene from 120 to 384 fps. Your other video
              settings are left alone.
            </span>
            <div class="notice-action">
              <Button small disabled={busy} onClick={unlockFrames}>Remove the limit</Button>
            </div>
          </div>
        </Notice>
      </div>
    {/if}

    <!-- OPTIFINE: RENDER REGIONS -->
    {#if view.renderRegions}
      <!-- The biggest terrain saving Fathom's view profile found is OptiFine's
           own, switched off by default. Fathom does not duplicate it and does
           not turn it on behind anyone's back: it says what it measured and
           offers the switch. -->
      <Section label="OptiFine">
        <Row
          title="Render Regions"
          detail={view.renderRegions.vboOff
            ? `${view.renderRegions.summary} It needs VBOs, which are off in this profile's video settings.`
            : view.renderRegions.summary}
          badge={view.renderRegions.on ? 'On' : 'Off'}
          tone={view.renderRegions.on ? 'good' : 'neutral'}
          compact
        >
          <svelte:fragment slot="control">
            <Switch
              checked={view.renderRegions.on}
              disabled={busy}
              label="Render Regions"
              onChange={chooseRenderRegions}
            />
          </svelte:fragment>
        </Row>
      </Section>
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
            <CircleCheck size={ICON.tile} />
          {:else}
            <CircleAlert size={ICON.tile} />
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
      material
    >
      {#if view.benchmark}
        <Row
          title={view.benchmark.headline}
          detail="Recorded in game on this machine. Not a comparison against anything."
          badge={view.benchmark.partial ? 'Partial' : view.benchmark.label}
          tone={view.benchmark.partial ? 'warn' : 'good'}
          expandable
        >
          <span slot="icon"><Gauge size={ICON.tile} /></span>
          <svelte:fragment slot="details">
            <div class="metrics">
              <div class="metric-group">
                <h4>Frames</h4>
                {#each view.benchmark.frames as metric}
                  <div class="metric">
                    <span>{metric.label}</span><span class="mono">{metric.value}</span>
                  </div>
                {/each}
              </div>
              <div class="metric-group">
                <h4>Memory</h4>
                {#each view.benchmark.memory as metric}
                  <div class="metric">
                    <span>{metric.label}</span><span class="mono">{metric.value}</span>
                  </div>
                {/each}
              </div>
              <div class="metric-group">
                <h4>Garbage collection</h4>
                {#each view.benchmark.gc as metric}
                  <div class="metric">
                    <span>{metric.label}</span><span class="mono">{metric.value}</span>
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
        <EmptyState
          compact
          icon={Gauge}
          title="No performance history"
          detail="Press Right Shift in game, open Diagnostics and start a benchmark. The result appears here."
        />
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
                      <!-- Live state from the running game: the one place on this
                           page the signal colour belongs. -->
                      <span
                        class="live"
                        role="img"
                        aria-label="Running now"
                        use:tooltip={'Running now'}
                        transition:fade={{ duration: FAST }}
                      ></span>
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
    {#if view.planned > 0}
      <!-- Not rows: an entry nobody has implemented has nothing to switch, and
           listing them dimmed under "Needs module" read as the module missing. -->
      <p class="planned">
        {view.planned} more {view.planned === 1 ? 'optimization is' : 'optimizations are'} planned.
        They appear here once the module implements them.
      </p>
    {/if}
  </Page>
{/if}

<style>
  /* A one-off condition with an action, not a setting: a notice that removes
     itself when `videoLimits` comes back null. */
  .notice-text {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .notice-text strong {
    font-weight: var(--type-heading-weight);
    color: var(--color-text);
  }

  .notice-text span {
    color: var(--color-text-secondary);
  }

  .notice-action {
    display: flex;
    margin-top: var(--space-2);
  }

  /* The segmented control keeps its own width; stretched to the column it
     would leave an empty length of track beyond the last option. */
  .preset {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-3);
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
  .planned {
    margin: var(--space-4) 0 0;
    font-size: var(--type-caption-size);
    color: var(--color-text-secondary);
  }

  .groups {
    display: flex;
    flex-direction: column;
    gap: 0;
  }

  .groups :global(section) {
    padding: 7px 0;
    box-shadow: 0 calc(-1 * var(--hairline)) 0 var(--control-hairline);
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
    margin: 0 0 var(--space-2);
    font-size: var(--type-label-size);
    font-weight: var(--type-label-weight);
    letter-spacing: var(--type-label-tracking);
    color: var(--color-text-tertiary);
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
    font-size: var(--type-caption-size);
    color: var(--color-text);
  }

  .caveats {
    margin: var(--s-3) 0 0;
    padding-left: 18px;
    font-size: var(--t-caption);
    color: var(--text-faint);
    line-height: 1.5;
  }

  .module-icon {
    color: var(--color-warning-text);
  }

  .module-icon.on {
    color: var(--color-success-text);
  }

  /* A 6px signal dot with a comfortable hover target around it. */
  .live {
    display: grid;
    place-items: center;
    width: 20px;
    height: 20px;
  }

  .live::before {
    content: '';
    width: 6px;
    height: 6px;
    border-radius: var(--radius-pill);
    background-color: var(--color-signal);
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
    border-top: var(--hairline) solid var(--chrome-line);
  }

  dl div {
    display: flex;
    gap: var(--s-3);
  }

  dt {
    flex-shrink: 0;
    width: 110px;
    color: var(--color-text-tertiary);
    font-size: var(--type-caption-size);
  }

  dd {
    margin: 0;
    flex: 1;
  }
</style>
