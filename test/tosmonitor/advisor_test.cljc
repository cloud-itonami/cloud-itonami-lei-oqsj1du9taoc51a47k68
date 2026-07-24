(ns tosmonitor.advisor-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tosmonitor.advisor :as advisor]
            [tosmonitor.store :as store]))

(deftest identical-candidate-is-non-material
  (let [db (store/seed-db)
        bl (store/baseline db)
        p  (advisor/infer db {:candidate bl})]
    (is (false? (:material-change? p)))
    (is (= :baseline/reconfirm (:effect p)))
    (is (= [] (:cites p)))
    (is (= :actuation/archive-update (:stake p))
        "stake is always set, material or not -- ADR-2607241900 Decision 4")))

(deftest divergent-candidate-is-material-and-cites-the-diff
  (let [db (store/seed-db)
        bl (store/baseline db)
        c  (assoc bl :full-text (str (:full-text bl) "\nA brand new clause never seen before."))
        p  (advisor/infer db {:candidate c})]
    (is (true? (:material-change? p)))
    (is (= :baseline/supersede (:effect p)))
    (is (= ["A brand new clause never seen before."] (:cites p)))
    (is (every? #(str/includes? (:full-text c) %) (:cites p))
        "the mock advisor only ever cites grounded excerpts")))

(deftest force-cites-overrides-detected-diff
  (testing "models what a misbehaving LLM might claim, independent of ground truth"
    (let [db (store/seed-db)
          bl (store/baseline db)
          p  (advisor/infer db {:candidate bl :force-cites ["a fabricated quote"]})]
      (is (= ["a fabricated quote"] (:cites p))))))

(deftest force-material-overrides-detected-equality
  (let [db (store/seed-db)
        bl (store/baseline db)
        p  (advisor/infer db {:candidate bl :force-material? true})]
    (is (true? (:material-change? p)))))
