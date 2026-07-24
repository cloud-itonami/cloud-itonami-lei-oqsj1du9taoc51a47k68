(ns tosmonitor.store
  "SSoT for the ToSMonitor actor, behind a `Store` protocol so the backend is
  a swap, not a rewrite -- the same seam every prior `cloud-itonami-isic-*`/
  `cloud-itonami-*` actor in this fleet uses (closest template:
  `commitledger.store`, `cloud-itonami-commitment-ledger`, ADR-2607241800).

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store. Pure `.cljc`, so it runs offline AND can
                        be pointed at a real Datomic Local or a
                        kotoba-server pod by swapping `langchain.db`'s
                        `:db-api`.

  `DatomicStore` uses `langchain-store.core` (ADR-2607141600) for the shared
  EDN-blob codec (`ls/enc`/`ls/dec*`) instead of hand-rolling it.

  This repo archives exactly ONE company (Starbucks Corporation, LEI
  OQSJ1DU9TAOC51A47K68) -- unlike `commitledger.store`'s multi-application
  directory, this Store holds a SINGLE `:company` record and a SINGLE
  `:baseline` record (the latest archived ToS snapshot this actor treats as
  ground truth to compare candidates against), plus the append-only audit
  ledger and change-proposal draft history.

  IMPORTANT: this Store NEVER reads `80-data/public/tos.journal.edn` off
  disk, and `commit-record!` NEVER writes to it either -- the archive file
  stays exactly what ADR-2607110300 defines, owned by that ADR's own
  discipline. `tosmonitor.registry/fold-quad-log` is a pure bridge a caller
  (not this Store) can use to turn that file's quad-log EDN into the shape
  `with-baseline` accepts, matching every sibling actor's own
  injection-boundary convention (Store construction stays pure/offline; real
  data is a swap, not baked into the actor). `demo-baseline`'s full text is
  a SHORT, hand-written representative excerpt (not the real, page-length
  archived text) -- matching `cloud-itonami-lei-2572ibtt8cczw6au4141`'s own
  pilot convention (ADR-2607241900) -- but its :source-url/:retrieved-at/
  :doc-type are this company's REAL archived values (`80-data/public/
  tos.journal.edn`), and :company matches this repo's own `blueprint.edn`."
  (:require [langchain.db :as d]
            [langchain-store.core :as ls]
            [tosmonitor.registry :as registry]))

(defprotocol Store
  (company [s])
  (baseline [s])
  (ledger [s])
  (change-proposal-history [s] "append-only history of committed :tos/change-proposal records (material and non-material alike)")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-company [s company] "replace/seed the company identity record")
  (with-baseline [s baseline] "replace/seed the baseline (latest archived ToS snapshot)"))

;; ----------------------------- demo data -----------------------------
;; A short, self-contained fixture (not the real archived page, which is
;; page-length with nav-menu boilerplate) -- see ns docstring.

(defn demo-company []
  {:legal-name "Starbucks Corporation"
   :website    "https://www.starbucks.com"})

(def ^:private demo-full-text
  "Terms of Use\nWelcome to starbucks.com. By using this website you agree to these Terms of Use, which govern your access to our coffeehouse locator, online ordering, and Starbucks Rewards content. This site is operated by Starbucks Corporation for informational purposes only and is subject to change without notice. These Terms are governed by the laws of the State of Washington.")

(defn demo-baseline []
  {:full-text    demo-full-text
   :source-url   "https://www.starbucks.com/terms/"
   :retrieved-at "2026-07-10"
   ;; computed from `demo-full-text` itself so the fixture is internally
   ;; self-consistent (:clj-only ground truth, see `tosmonitor.registry` ns
   ;; docstring; :cljs falls back to a fixed placeholder)
   :sha256       #?(:clj (registry/sha256-hex demo-full-text)
                     :cljs "0000000000000000000000000000000000000000000000000000000000000000")
   :doc-type     :terms-of-use})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (company [_] (:company @a))
  (baseline [_] (:baseline @a))
  (ledger [_] (:ledger @a))
  (change-proposal-history [_] (:change-proposal-history @a))
  (commit-record! [s {:keys [effect value]}]
    (case effect
      :baseline/reconfirm (swap! a assoc :baseline (merge (baseline s) value))
      :baseline/supersede  (swap! a assoc :baseline value)
      nil)
    (when (#{:baseline/reconfirm :baseline/supersede} effect)
      (swap! a update :change-proposal-history conj {:effect effect :value value}))
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-company [s c] (when c (swap! a assoc :company c)) s)
  (with-baseline [s b] (when b (swap! a assoc :baseline b)) s))

(defn seed-db
  "A MemStore seeded with the demo company/baseline. The deterministic
  default. `opts` (both optional) let a caller inject real data (e.g.
  `tosmonitor.registry/fold-quad-log`'s output) without changing this
  namespace:
    :company  -- {:legal-name .. :website ..}
    :baseline -- {:full-text .. :source-url .. :retrieved-at .. :sha256 .. :doc-type ..}"
  [& [{:keys [company baseline]}]]
  (->MemStore (atom {:company (or company (demo-company))
                      :baseline (or baseline (demo-baseline))
                      :ledger [] :change-proposal-history []})))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "One singleton entity (:record/id \"singleton\") holds both company and
  baseline fields -- this repo archives exactly one company, so there is no
  need for a directory/collection shape (contrast `commitledger.store`'s
  per-application entities). Compound/blob values (ledger facts,
  change-proposal drafts) are stored as EDN strings via `langchain-store`'s
  codec so `langchain.db` doesn't try to expand them into sub-entities."
  {:record/id  {:db/unique :db.unique/identity}
   :ledger/seq {:db/unique :db.unique/identity}
   :draft/seq  {:db/unique :db.unique/identity}})

(defn- singleton->tx [{:keys [company baseline]}]
  (cond-> {:record/id "singleton"}
    (:legal-name company)  (assoc :company/legal-name (:legal-name company))
    (:website company)     (assoc :company/website (:website company))
    (:full-text baseline)    (assoc :baseline/full-text (:full-text baseline))
    (:source-url baseline)    (assoc :baseline/source-url (:source-url baseline))
    (:retrieved-at baseline)   (assoc :baseline/retrieved-at (:retrieved-at baseline))
    (:sha256 baseline)          (assoc :baseline/sha256 (:sha256 baseline))
    (:doc-type baseline)         (assoc :baseline/doc-type (:doc-type baseline))))

(def ^:private singleton-pull
  [:company/legal-name :company/website
   :baseline/full-text :baseline/source-url :baseline/retrieved-at
   :baseline/sha256 :baseline/doc-type])

(defn- pull->singleton [m]
  {:company  {:legal-name (:company/legal-name m) :website (:company/website m)}
   :baseline {:full-text (:baseline/full-text m) :source-url (:baseline/source-url m)
              :retrieved-at (:baseline/retrieved-at m) :sha256 (:baseline/sha256 m)
              :doc-type (:baseline/doc-type m)}})

(defn- singleton [conn]
  (pull->singleton (d/pull (d/db conn) singleton-pull [:record/id "singleton"])))

(defrecord DatomicStore [conn]
  Store
  (company [_] (:company (singleton conn)))
  (baseline [_] (:baseline (singleton conn)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (change-proposal-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :draft/seq ?s] [?e :draft/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (commit-record! [s {:keys [effect value]}]
    (case effect
      :baseline/reconfirm
      (d/transact! conn [(singleton->tx {:baseline (merge (baseline s) value)})])

      :baseline/supersede
      (d/transact! conn [(singleton->tx {:baseline value})])

      nil)
    (when (#{:baseline/reconfirm :baseline/supersede} effect)
      (d/transact! conn [{:draft/seq (count (change-proposal-history s))
                           :draft/record (ls/enc {:effect effect :value value})}]))
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (ls/enc fact)}])
    fact)
  (with-company [s c] (when c (d/transact! conn [(singleton->tx {:company c})])) s)
  (with-baseline [s b] (when b (d/transact! conn [(singleton->tx {:baseline b})])) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data` ({:company ..
  :baseline ..}); demo data when omitted."
  ([] (datomic-store {:company (demo-company) :baseline (demo-baseline)}))
  ([{:keys [company baseline]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-company (or company (demo-company)))
         (with-baseline (or baseline (demo-baseline)))))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo company/baseline -- the Datomic-backed
  analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store))
