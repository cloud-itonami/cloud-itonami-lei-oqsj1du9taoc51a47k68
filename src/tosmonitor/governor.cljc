(ns tosmonitor.governor
  "ToSArchiveGovernor -- the independent compliance layer that earns the
  ToSMonitor-LLM the right to commit. The LLM has no notion of whether a
  quoted excerpt is actually present in the candidate text, whether the
  candidate's own provenance is complete, whether its self-reported SHA-256
  actually matches its own content, whether it is older than what is
  already archived, whether its doc-type is one this archive recognizes, or
  whether its source URL actually belongs to the company this repo
  archives -- so this MUST be a separate system able to *reject* a proposal
  and fall back to HOLD, the ToS-archive analog of `commitledger.governor`
  (`cloud-itonami-commitment-ledger`, ADR-2607241800) and, further back,
  `credit.governor`/`factoring.governor`.

  Six HARD checks gate `:tos/change-proposal` (the only actuation this actor
  performs). ALL HARD violations: a human archivist CANNOT override them.
  The confidence/material-change gate is SOFT in form but, per
  `tosmonitor.phase`, NEVER auto-eligible at any phase when the proposal is
  `:material-change? true` -- two independent layers (this governor's
  `high-stakes` set AND `tosmonitor.phase`'s per-phase `:auto` set) agree
  that changing what the public record says a company's legal terms are is
  always a human's call. This actor does NOT write to the archive-of-record
  (`80-data/public/tos.journal.edn`) itself -- see `tosmonitor.store` ns
  docstring; a commit here only records that a human archivist should
  review a candidate.

  ## The six `:tos/change-proposal` HARD checks

    1. `grounding-violations`                -- every excerpt in the proposal's `:cites` must be an exact substring of the candidate's own full text (`tosmonitor.registry/quote-grounded?`). No hallucinated quotes.
    2. `provenance-incomplete-violations`     -- the candidate must carry non-blank full-text/source-url/retrieved-at/sha256 (`tosmonitor.registry/provenance-complete?`).
    3. `sha256-mismatch-violations`           -- ground-truth recompute (`tosmonitor.registry/sha256-hex`) of the candidate's own full text; any mismatch against its own carried value is HARD. `nil` recompute (`:cljs` build, see `tosmonitor.registry` ns docstring) never trips this check -- ground truth unavailable is not treated as a fabricated mismatch.
    4. `retrieved-at-not-advancing-violations` -- the candidate's own retrieval date must be `>=` the archived baseline's own retrieval date (`tosmonitor.registry/retrieved-at-advancing?`).
    5. `doc-type-unknown-violations`          -- the candidate's own doc-type must be a member of `tosmonitor.registry/known-doc-types`.
    6. `source-domain-mismatch-violations`     -- the candidate's own source-url domain must match (or be a subdomain of) the STORE's own company website domain (`tosmonitor.registry/source-domain-matches-company?`) -- independently re-verified from the store, never the advisor's self-report. This actor's own DISTINCTIVE check, per this fleet's convention that every actor's ADR names its own novel contribution (see ADR-2607241900 Decision 5) -- it guards the risk specific to an LEI-keyed independent-archive family: misattributing a document to the wrong company."
  (:require [tosmonitor.registry :as registry]
            [tosmonitor.store :as store]))

(def confidence-floor
  "Below this, a clean proposal still escalates to a human."
  0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean. This
  actor performs one real-world actuation -- proposing a change to what the
  public record says a company's legal terms are. It never writes the
  archive-of-record itself (see ns docstring)."
  #{:actuation/archive-update})

(def ^:private checked-ops #{:tos/change-proposal})

;; ----------------------------- grounding -----------------------------

(defn- grounding-violations
  "Check 1. Every excerpt the proposal cites must be an exact substring of
  the candidate's own full text. Re-verified against the REQUEST's own
  candidate text, never the proposal's self-reported evidence alone."
  [{:keys [op candidate]} proposal]
  (when (contains? checked-ops op)
    (let [bad (remove #(registry/quote-grounded? (:full-text candidate) %) (:cites proposal))]
      (when (seq bad)
        [{:rule :grounding
          :detail (str "proposal cites " (count bad) " excerpt(s) not found verbatim in the candidate's own text: " (pr-str (vec bad)))}]))))

;; ----------------------------- provenance -----------------------------

(defn- provenance-incomplete-violations
  "Check 2."
  [{:keys [op candidate]}]
  (when (contains? checked-ops op)
    (when-not (registry/provenance-complete? candidate)
      [{:rule :provenance-incomplete
        :detail "candidate is missing one of full-text/source-url/retrieved-at/sha256"}])))

(defn- sha256-mismatch-violations
  "Check 3. Ground-truth recompute, independent of whatever the candidate
  itself claims. `nil` recompute (ground truth unavailable in this build)
  never trips this check -- see ns docstring."
  [{:keys [op candidate]}]
  (when (contains? checked-ops op)
    (let [recomputed (registry/sha256-hex (:full-text candidate))]
      (when (and recomputed (not= recomputed (:sha256 candidate)))
        [{:rule :sha256-mismatch
          :detail (str "recomputed sha256 (" recomputed ") does not match candidate's own sha256 (" (:sha256 candidate) ")")}]))))

(defn- retrieved-at-not-advancing-violations
  "Check 4."
  [{:keys [op candidate]} st]
  (when (contains? checked-ops op)
    (when-not (registry/retrieved-at-advancing? (:retrieved-at candidate) (:retrieved-at (store/baseline st)))
      [{:rule :retrieved-at-not-advancing
        :detail (str "candidate retrieved-at (" (:retrieved-at candidate)
                     ") is older than the archived baseline's own retrieved-at (" (:retrieved-at (store/baseline st)) ")")}])))

(defn- doc-type-unknown-violations
  "Check 5."
  [{:keys [op candidate]}]
  (when (contains? checked-ops op)
    (when-not (contains? registry/known-doc-types (:doc-type candidate))
      [{:rule :doc-type-unknown
        :detail (str "candidate doc-type " (pr-str (:doc-type candidate)) " is not one of " (pr-str registry/known-doc-types))}])))

;; ----------------------------- distinctive check: source-domain -----------------------------

(defn- source-domain-mismatch-violations
  "Check 6. This actor's own DISTINCTIVE check -- see ns docstring."
  [{:keys [op candidate]} st]
  (when (contains? checked-ops op)
    (when-not (registry/source-domain-matches-company? (:source-url candidate) (:website (store/company st)))
      [{:rule :source-domain-mismatch
        :detail (str "candidate source-url (" (:source-url candidate)
                     ") domain does not match the archived company's own website (" (:website (store/company st)) ")")}])))

;; ----------------------------- check aggregation -----------------------------

(defn check
  "Censors a ToSMonitor-LLM proposal against the governor rules. Returns
   {:ok? bool :violations [..] :confidence c :hard? bool :escalate? bool :high-stakes? bool}."
  [request _context proposal st]
  (let [violation-lists [(grounding-violations request proposal)
                          (provenance-incomplete-violations request)
                          (sha256-mismatch-violations request)
                          (retrieved-at-not-advancing-violations request st)
                          (doc-type-unknown-violations request)
                          (source-domain-mismatch-violations request st)]
        hard    (into [] (apply concat violation-lists))
        conf    (:confidence proposal 0.0)
        stakes? (boolean (high-stakes (:stake proposal)))]
    {:ok?          (and (empty? hard) (>= conf confidence-floor) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        (boolean (seq hard))
     :escalate?    (and (empty? hard) (or (< conf confidence-floor) stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
