(ns tosmonitor.store-contract-test
  "The Store contract as executable tests, run against BOTH backends
  (MemStore | DatomicStore) -- the whole point of the `Store` protocol seam
  is that the actor, the ToSArchiveGovernor and the audit ledger never know
  which one they run on (`tosmonitor.store` ns docstring)."
  (:require [clojure.test :refer [deftest is testing]]
            [tosmonitor.store :as store]))

(def ^:private backends
  {:mem     store/seed-db
   :datomic store/datomic-seed-db})

(deftest company-and-baseline-match-demo-data
  (doseq [[backend-name ctor] backends]
    (testing (str backend-name)
      (let [db (ctor)]
        (is (= (store/demo-company) (store/company db)))
        (is (= (store/demo-baseline) (store/baseline db)))))))

(deftest ledger-append-is-append-only-and-ordered
  (doseq [[backend-name ctor] backends]
    (testing (str backend-name)
      (let [db (ctor)]
        (is (= [] (store/ledger db)))
        (store/append-ledger! db {:t :a :seq 1})
        (store/append-ledger! db {:t :b :seq 2})
        (is (= [{:t :a :seq 1} {:t :b :seq 2}] (store/ledger db)))))))

(deftest commit-record-baseline-supersede-updates-baseline
  (doseq [[backend-name ctor] backends]
    (testing (str backend-name)
      (let [db (ctor)
            new-baseline {:full-text "new text" :source-url "https://us.pg.com/terms"
                          :retrieved-at "2026-08-01" :sha256 "deadbeef"
                          :doc-type :terms-of-service}]
        (store/commit-record! db {:effect :baseline/supersede :value new-baseline})
        (is (= new-baseline (store/baseline db)))
        (is (= 1 (count (store/change-proposal-history db))))))))

(deftest commit-record-baseline-reconfirm-merges-into-baseline
  (doseq [[backend-name ctor] backends]
    (testing (str backend-name)
      (let [db (ctor)]
        (store/commit-record! db {:effect :baseline/reconfirm :value {:retrieved-at "2026-08-01"}})
        (is (= "2026-08-01" (:retrieved-at (store/baseline db))))
        (is (= (:full-text (store/demo-baseline)) (:full-text (store/baseline db)))
            "reconfirm only merges the patch; unrelated baseline fields are unchanged")))))
