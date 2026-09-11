(ns tosmonitor.phase
  "Phase 0->3 staged rollout -- the ToS-archive analog of `commitledger.phase`
  (`cloud-itonami-commitment-ledger`, ADR-2607241800) and, further back,
  `credit.phase`/`factoring.phase`.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-monitor -- :tos/change-proposal allowed, every write
                                 needs human approval.
    Phase 2  assisted-monitor -- same write set as phase 1 (this domain has
                                 no separate no-actuation-risk lifecycle
                                 distinct from the check itself -- see
                                 `tosmonitor.store`'s own docstring: a
                                 single company, no sub-record).
    Phase 3  supervised       -- label only. `:tos/change-proposal` is
                                 PERMANENTLY absent from every phase's
                                 `:auto` set, including phase 3 -- a
                                 structural fact, not a rollout milestone
                                 still to come.

  `:tos/change-proposal` is a member of `write-ops` (governor-gated like any
  write) but is NEVER a member of any phase's `:auto` set below -- do not
  add it there. This pilot deliberately ships ONE always-escalate actuation
  (ADR-2607241900 Decision 4) rather than also building a lower-stakes,
  auto-eligible 'routine reconfirmation' op; `tosmonitor.governor`'s
  `high-stakes` set (`:actuation/archive-update`, always set on every
  proposal regardless of `:material-change?`) independently agrees --
  before the phase gate below even runs, a clean proposal already carries
  disposition `:escalate`, not `:commit`, so the phase `:auto` check never
  gets a chance to fire for this op at all. Two layers, not one, agree that
  recording a verdict on a company's ToS is always a human's call.")

(def read-ops  #{})
(def write-ops #{:tos/change-proposal})

;; NOTE the invariant: `:tos/change-proposal` is a member of `write-ops`
;; (governor-gated like any write) but is NEVER a member of any phase's
;; `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"        :writes #{}         :auto #{}}
   1 {:label "assisted-monitor" :writes write-ops    :auto #{}}
   2 {:label "assisted-monitor" :writes write-ops    :auto #{}}
   3 {:label "supervised"       :writes write-ops    :auto #{}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:tos/change-proposal` is never auto-eligible at any phase, so it
    always escalates once the governor clears it (or holds if it doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [p (if (contains? phases phase) phase default-phase)
        {:keys [writes auto]} (get phases p {:writes #{} :auto #{}})]
    (cond
      (= governor-disposition :hold)
      {:disposition :hold :reason nil}

      (not (contains? writes op))
      {:disposition :hold :reason :phase-disabled}

      (and (= governor-disposition :commit) (not (contains? auto op)))
      {:disposition :escalate :reason :phase-approval}

      :else
      {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a ToSArchiveGovernor verdict to a base disposition before the phase
  gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
