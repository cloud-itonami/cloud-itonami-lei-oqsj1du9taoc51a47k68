(ns tosmonitor.advisor
  "ToSMonitor-LLM client -- the *contained intelligence node*. It compares a
  supplied candidate ToS/legal-document snapshot against the archived
  baseline and proposes either a reconfirmation (nothing material changed) or
  a supersede (it did). CRITICAL: it is a smart-but-untrusted advisor. It
  returns a *proposal* (with cited evidence), never a committed record.
  Every output is censored downstream by `tosmonitor.governor` before
  anything touches the SSoT -- and the archive-of-record
  (`80-data/public/tos.journal.edn`) is never touched at all (see
  `tosmonitor.store` ns docstring).

  Like every sibling actor's advisor, this is a deterministic mock by
  default so the actor graph runs offline and the governor contract is
  exercised end-to-end. In production this would call a real LLM with the
  same proposal shape.

  Proposal shape:
    {:summary          str            ; human-facing draft
     :rationale         str            ; why -- cites the detected divergence
     :cites             [str ..]       ; verbatim excerpts the LLM used as evidence -- SCANNED by grounding-violations
     :material-change?  bool
     :effect            :baseline/reconfirm | :baseline/supersede
     :value             map            ; what a commit would apply to the SSoT
     :stake             :actuation/archive-update | nil
     :confidence        0..1}

  Request may carry `:force-cites`/`:force-material?` -- these model what
  the LLM itself CLAIMS (possibly wrongly), independent of the candidate's
  own ground-truth fields, so tests can exercise
  `tosmonitor.governor/grounding-violations` and the high-stakes gate
  without needing a live model to misbehave on cue."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.model :as model]
            [tosmonitor.store :as store]))

(defn- diff-excerpt
  "Deterministic mock diff: the first non-blank line present in the
  candidate's own text but absent from the baseline's. `nil` when every
  candidate line is already present in the baseline (no detected
  divergence)."
  [baseline-text candidate-text]
  (let [baseline-lines (set (str/split-lines (or baseline-text "")))]
    (->> (str/split-lines (or candidate-text ""))
         (remove #(or (str/blank? %) (contains? baseline-lines %)))
         first)))

(defn infer
  "request: {:op :tos/change-proposal :subject id
             :candidate {:full-text .. :source-url .. :retrieved-at .. :sha256 .. :doc-type ..}
             :force-cites [..]        ; optional, test-only LLM-claim override
             :force-material? bool}   ; optional, test-only LLM-claim override"
  [st {:keys [candidate force-cites force-material?]}]
  (let [bl        (store/baseline st)
        same-text? (= (str/trim (or (:full-text candidate) ""))
                       (str/trim (or (:full-text bl) "")))
        diff      (diff-excerpt (:full-text bl) (:full-text candidate))
        material? (if (some? force-material?) (boolean force-material?)
                      (and (not same-text?) (some? diff)))
        cites     (cond
                    (seq force-cites) (vec force-cites)
                    (and material? diff) [diff]
                    :else [])]
    {:summary    (if material?
                   "Candidate text differs from the archived baseline; proposing supersede."
                   "Candidate text matches the archived baseline; reconfirming currency.")
     :rationale  (if material?
                   (str "Detected a line present in the candidate but absent from the baseline: "
                        (pr-str diff))
                   "No divergence detected from the baseline full text.")
     :cites      cites
     :material-change? material?
     :effect     (if material? :baseline/supersede :baseline/reconfirm)
     :value      (if material?
                   (select-keys candidate [:full-text :source-url :retrieved-at :sha256 :doc-type])
                   {:retrieved-at (:retrieved-at candidate)})
     ;; ALWAYS set, material or not (ADR-2607241900 Decision 4) -- recording
     ;; any verdict on a company's ToS, changed or unchanged, is treated as
     ;; the actuation. tosmonitor.governor's high-stakes gate and
     ;; tosmonitor.phase's permanent :auto exclusion independently agree
     ;; this is never autonomous, for every :tos/change-proposal run.
     :stake      :actuation/archive-update
     :confidence (cond (seq force-cites) 0.9 material? 0.85 :else 0.95)}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "You are a legal-document change-monitoring advisor. Given a baseline "
       "document and a candidate document, return exactly one proposal as an "
       "EDN map, nothing else. Keys: :summary :rationale "
       ":cites (a vector of VERBATIM substrings copied from the candidate "
       "text, never paraphrased) :material-change? (bool) "
       ":effect (:baseline/reconfirm or :baseline/supersede) "
       ":confidence (0..1). "
       "IMPORTANT: every string in :cites MUST be an exact, character-for-"
       "character substring of the candidate text -- never invent or "
       "paraphrase a quote."))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure yields
  a safe low-confidence non-material proposal so the ToSArchiveGovernor
  escalates/holds -- an LLM hiccup can never auto-commit."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :baseline/reconfirm))
          (update :material-change? boolean))
      {:summary "Could not interpret the model response" :rationale (str content)
       :cites [] :material-change? false :effect :baseline/reconfirm :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference). Pass
  `model/anthropic-model`, an OpenAI-compatible model, or `model/mock-model`
  for offline tests. NOT invoked anywhere in this pilot's `sim.cljc` or test
  suite (see ADR-2607241900 Decision 6) -- written as the swappable seam
  every sibling actor's advisor namespace exposes."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st {:keys [candidate] :as _req}]
       (let [bl   (store/baseline st)
             msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "Baseline:\n" (pr-str bl)
                                              "\nCandidate:\n" (pr-str candidate))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- the LLM's interpretable rationale is a
  key asset (later dispute resolution). Persisted to the :audit channel."
  [request proposal]
  {:t          :advisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
