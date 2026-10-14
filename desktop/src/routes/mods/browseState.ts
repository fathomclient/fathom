/**
 * The Browse tab's state, kept outside the component.
 *
 * Opening a mod replaces the Mods page with that mod's page, which unmounts
 * the Browse tab. Without this, coming back would mean a new search, a new
 * round of compatibility checks and a list scrolled to the top — the browser
 * forgetting what you were doing every time you looked at something. So the
 * filters, the loaded results, the verdicts and the scroll position live here
 * for the session, keyed by the profile they were for.
 *
 * Nothing here is saved to disk: it is a convenience, not a history.
 */
import type {
  Breadth,
  CatalogEntry,
  CatalogSort,
  ProviderOutcome,
  VerdictView,
} from '../../lib/api';

export interface BrowseState {
  profileId: string;
  text: string;
  category: string;
  sort: CatalogSort;
  breadth: Breadth;
  entries: CatalogEntry[];
  verdicts: Record<string, VerdictView>;
  providers: ProviderOutcome[];
  nextOffset: number;
  hasMore: boolean;
  /** Whether `entries` answers the filters above. */
  loaded: boolean;
  scrollTop: number;
}

export function freshState(profileId: string): BrowseState {
  return {
    profileId,
    text: '',
    category: '',
    sort: 'relevance',
    breadth: 'plausible',
    entries: [],
    verdicts: {},
    providers: [],
    nextOffset: 0,
    hasMore: false,
    loaded: false,
    scrollTop: 0,
  };
}

let kept: BrowseState | null = null;

export function restore(profileId: string): BrowseState {
  if (kept && kept.profileId === profileId) return kept;
  kept = freshState(profileId);
  return kept;
}

export function keep(state: BrowseState): void {
  kept = state;
}
