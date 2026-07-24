(ns tosmonitor.registry
  "Ground-truth calculations for the ToSMonitor actor -- pure functions the
  ToSArchiveGovernor recomputes independently rather than trusting the
  ToSMonitor-LLM's self-report, matching this fleet's standing convention
  (`commitledger.registry`, `credit.registry`, `factoring.registry`: 'reused,
  not shared code across the repo boundary' -- every actor re-implements its
  own ground-truth helpers locally).

  `sha256-hex` is the one HONEST V1 SCOPE BOUNDARY in this namespace: it is
  `:clj`-only. The Web Crypto `SubtleCrypto.digest` API every `:cljs` build
  would otherwise use is Promise-based (async), and a governor check must be
  a synchronous pure function -- there is no portable synchronous SHA-256 in
  ClojureScript without a bundled JS dependency, which this fleet's `.cljc`
  actors avoid. `sha256-hex` therefore returns `nil` under `:cljs`, and
  `tosmonitor.governor/sha256-mismatch-violations` treats a `nil` recompute
  as 'ground truth unavailable in this build' rather than a fabricated
  match/mismatch -- documented, not silently assumed, matching this fleet's
  honest-scope-boundary convention (`commitledger.registry`'s JPN-only
  rate-ceiling tiers, its own no-live-HTTP self-registration check)."
  (:require [clojure.string :as str]))

;; ----------------------------- doc-type vocabulary -----------------------------

(def known-doc-types
  "Matches the archive family's OWN observed :tos/doc-type vocabulary
  (surveyed across all 155 `cloud-itonami-lei-*` `80-data/public/
  tos.journal.edn` files, ADR-2607110300; not invented/guessed values)."
  #{:terms-of-service :privacy-policy :terms-of-use :legal-notice
    :terms-of-carriage :terms-and-conditions :legal-disclaimer
    :vendor-terms-and-conditions :risk-disclosures :policies-and-guidelines
    :legal-hub :contract-of-carriage})

;; ----------------------------- ground-truth SHA-256 -----------------------------

(defn sha256-hex
  "Ground-truth recompute of `text`'s SHA-256, lowercase 64-char hex -- the
  same digest shape `80-data/public/tos.journal.edn`'s own `:tos/sha256`
  entries carry. `nil` under `:cljs` (see ns docstring)."
  [text]
  #?(:clj
     (when (string? text)
       (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                              (.getBytes ^String text "UTF-8"))]
         (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))
     :cljs
     nil))

(defn ground-truth-sha256-available?
  "Whether this build can independently recompute SHA-256 at all (see ns
  docstring's V1 scope boundary)."
  []
  #?(:clj true :cljs false))

;; ----------------------------- quote grounding -----------------------------

(defn quote-grounded?
  "An excerpt is grounded iff it is an exact substring of the candidate's own
  full text -- the anti-fabrication primitive `tosmonitor.governor/grounding-
  violations` is built on. No fuzzy/normalized matching: a paraphrase is NOT
  grounded, by design -- the ToSMonitor-LLM must quote, not summarize, when
  citing evidence for a proposed change."
  [full-text excerpt]
  (and (string? full-text) (string? excerpt) (not (str/blank? excerpt))
       (str/includes? full-text excerpt)))

;; ----------------------------- provenance -----------------------------

(defn provenance-complete?
  "A candidate carries the same four provenance fields
  `80-data/public/tos.journal.edn`'s own quad-log requires of every archived
  entry: full text, source URL, retrieval date, and a SHA-256."
  [{:keys [full-text source-url retrieved-at sha256]}]
  (and (not (str/blank? full-text))
       (not (str/blank? source-url))
       (not (str/blank? retrieved-at))
       (not (str/blank? sha256))))

(defn retrieved-at-advancing?
  "ISO-8601 (YYYY-MM-DD) date strings compare correctly lexicographically.
  A candidate must be at-or-after the archived baseline's own retrieval
  date -- never propose a change from input staler than what is already
  on file."
  [candidate-retrieved-at baseline-retrieved-at]
  (and (string? candidate-retrieved-at)
       (or (nil? baseline-retrieved-at)
           (>= (compare candidate-retrieved-at baseline-retrieved-at) 0))))

;; ----------------------------- domain matching -----------------------------

(defn host-of
  "Extract the hostname from a URL string via regex (portable .cljc -- no
  java.net.URI/js URL dependency). `nil` for anything unparseable."
  [url]
  (when (string? url)
    (second (re-matches #"(?i)[a-z][a-z0-9+.\-]*://([^/?#:]+).*" url))))

(defn- base-domain
  "Naive base-domain (eTLD+1) extraction: the last two dot-separated labels
  (e.g. `termsandconditions.pg.com` -> `pg.com`, `us.pg.com` -> `pg.com`).
  Does not handle multi-part TLDs (`co.uk` etc.) -- an honest V1
  simplification without a bundled public-suffix-list dependency, matching
  this fleet's other documented scope boundaries."
  [host]
  (when (string? host)
    (let [parts (str/split host #"\.")]
      (if (>= (count parts) 2)
        (str/join "." (take-last 2 parts))
        host))))

(defn same-or-subdomain?
  "`host` and `company-host` share the same base domain -- e.g.
  `termsandconditions.pg.com` and `us.pg.com` both base to `pg.com` --
  case-insensitive. See `base-domain`'s own docstring for its V1 scope
  boundary."
  [host company-host]
  (and (string? host) (string? company-host)
       (= (base-domain (str/lower-case host)) (base-domain (str/lower-case company-host)))))

(defn source-domain-matches-company?
  "The candidate's own :source-url domain must share the same base domain
  as the company's own blueprint.edn :company/website -- independently
  re-verified from the store's own company record, never the advisor's
  self-report. This actor's own DISTINCTIVE check (see ns docstring /
  `tosmonitor.governor`): guards the risk specific to an LEI-keyed
  independent-archive family -- misattributing a document to the wrong
  company."
  [candidate-source-url company-website]
  (let [ch (host-of candidate-source-url)
        cw (host-of company-website)]
    (boolean (and ch cw (same-or-subdomain? ch cw)))))

;; ----------------------------- archive quad-log bridge -----------------------------

(defn fold-quad-log
  "Pure fold of the archive's own EDN quad-log shape
  (`80-data/public/tos.journal.edn`, ADR-2607110300: `[[entity attr value tx
  op] ...]`) into `{entity-id {attr value ...}}`, keyed by the KEYWORD tail of
  each namespaced attr (e.g. `:tos/retrieved-at` -> `:retrieved-at`) so the
  result lines up with `tosmonitor.store`'s baseline/candidate shape.

  V1 scope: `:add` tuples are folded (later tx wins on conflict, matching the
  quad-log's own append-only tx-ordering); a `:retract` tuple removes that
  attr if present. This is a bridge function only -- `tosmonitor.store`
  itself never reads a file off disk (every actor in this fleet keeps Store
  construction pure/offline; see `tosmonitor.store` ns docstring). A real
  deployment reads the archive file (`clojure.edn/read-string (slurp ...)`)
  and passes `(fold-quad-log quads)`'s result into `store/seed-db` as the
  `:baseline` option -- not wired into this pilot's `sim.cljc`, which uses a
  small inline representative baseline instead (see `tosmonitor.store`)."
  [quads]
  (reduce
   (fn [acc [entity attr value _tx op]]
     (let [k (keyword (name attr))]
       (case op
         :add     (assoc-in acc [entity k] value)
         :retract (update acc entity dissoc k)
         acc)))
   {}
   quads))
