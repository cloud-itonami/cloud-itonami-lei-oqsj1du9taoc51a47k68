(ns tosmonitor.operation
  "OperationActor -- one ToS-monitor check = one supervised actor run,
  expressed as a langgraph-clj StateGraph. The advisor (ToSMonitor-LLM) is
  sealed into a single node (:advise); its proposal is ALWAYS routed
  through the ToSArchiveGovernor (:govern) and the rollout phase gate
  (:decide) before anything commits to the SSoT.

  Everything the actor depends on is injected, so each is a swap, not a
  rewrite:
    - the Store    (MemStore | DatomicStore)                    - `store` arg
    - the Advisor  (mock | real LLM)                             - :advisor opt
    - the Phase    (0->3 rollout)                                - :phase in ctx

  One graph run = one `:tos/change-proposal` check (intake -> advise ->
  govern -> decide -> commit | hold | approval). No unbounded inner loop --
  each run is auditable and checkpointed. This actor NEVER writes the
  archive-of-record (`80-data/public/tos.journal.edn`) -- `commit` only
  writes to this actor's OWN Store (see `tosmonitor.store` ns docstring).

  Human-in-the-loop = real approval workflow: `interrupt-before
  #{:request-approval}` pauses the actor and hands the decision to a human
  archivist. The approver resumes with `{:approval {:status :approved}}`
  (or :rejected). `:tos/change-proposal` ALWAYS reaches this node when the
  governor is clean -- see `tosmonitor.phase`."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [tosmonitor.advisor :as advisor]
            [tosmonitor.governor :as governor]
            [tosmonitor.phase :as phase]
            [tosmonitor.store :as store]))

(defn- commit-fact [request context proposal]
  {:t          :committed
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :commit
   :basis      (:cites proposal)
   :summary    (:summary proposal)})

(defn- commit-record [_request _context proposal]
  {:effect (:effect proposal)
   :value  (:value proposal)})

(defn build
  "Compiles an OperationActor graph bound to `store` (any
  `tosmonitor.store/Store`).
  opts:
    :advisor      -- a `tosmonitor.advisor/Advisor` (default: mock-advisor)
    :checkpointer -- langgraph checkpointer (default: in-mem)"
  [store & [{:keys [advisor checkpointer]
             :or   {advisor      (advisor/mock-advisor)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}   ; injected actor-id/phase
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}   ; :commit | :hold | :escalate
         :record      {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      ;; ToSMonitor-LLM inference (the contained intelligence node) -- proposal only.
      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (advisor/-advise advisor store request)]
            {:proposal p :audit [(advisor/trace request p)]})))

      ;; ToSArchiveGovernor -- independent censor (separate system than the LLM).
      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (governor/check request context proposal store)}))

      ;; Decide: governor disposition, then the rollout-phase gate (which can
      ;; only add caution). HARD governor violations -> HOLD (no override).
      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (let [base (phase/verdict->disposition verdict)
                ph   (:phase context phase/default-phase)
                {:keys [disposition reason]} (phase/gate ph request base)]
            (case disposition
              :hold
              {:disposition :hold
               :audit [(cond-> (governor/hold-fact request context verdict)
                         reason (assoc :phase-reason reason :phase ph))]}

              :escalate
              {:disposition :escalate
               :audit [{:t :approval-requested
                        :op (:op request) :subject (:subject request)
                        :reason (or reason
                                    (cond (:high-stakes? verdict) :actuation
                                          :else :low-confidence))
                        :phase ph
                        :confidence (:confidence verdict)}]}

              :commit
              {:disposition :commit
               :record (commit-record request context proposal)}))))

      ;; Approval handoff -- paused by interrupt-before; a human archivist
      ;; resumes with :approval. Then route commit/hold.
      (g/add-node :request-approval
        (fn [{:keys [request context proposal approval verdict]}]
          (if (= :approved (:status approval))
            {:disposition :commit
             :record (assoc (commit-record request context proposal)
                            :value (assoc (:value proposal) :approved-by (:by approval)))
             :audit [{:t :approval-granted :op (:op request)
                      :subject (:subject request) :by (:by approval)}]}
            {:disposition :hold
             :audit [(merge (governor/hold-fact request context
                                                (assoc verdict :violations
                                                       [{:rule :approver-rejected}]))
                            {:t :approval-rejected})]})))

      ;; Commit -- the ONLY node that writes this actor's OWN Store (never
      ;; the archive-of-record) + audit ledger.
      (g/add-node :commit
        (fn [{:keys [request context proposal record]}]
          (store/commit-record! store record)
          (let [f (commit-fact request context proposal)]
            (store/append-ledger! store f)
            {:audit [f]})))

      ;; Hold -- write the rejection to the ledger; no SSoT mutation.
      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:governor-hold :approval-rejected} (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit   :commit
            :escalate :request-approval
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))
