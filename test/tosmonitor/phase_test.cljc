(ns tosmonitor.phase-test
  (:require [clojure.test :refer [deftest is testing]]
            [tosmonitor.phase :as phase]))

(deftest tos-change-proposal-never-in-any-auto-set
  (testing "structural invariant (ADR-2607241900 Decision 4): :tos/change-proposal
  is never auto-eligible at any phase, 0 through 3"
    (doseq [[p {:keys [auto]}] phase/phases]
      (is (not (contains? auto :tos/change-proposal))
          (str "phase " p " must not auto-eligible :tos/change-proposal")))))

(deftest phase-0-holds-any-write
  (testing "phase 0 is read-only -- a governor-clean commit still holds"
    (let [{:keys [disposition reason]} (phase/gate 0 {:op :tos/change-proposal} :commit)]
      (is (= :hold disposition))
      (is (= :phase-disabled reason)))))

(deftest phases-1-through-3-clean-commit-always-escalates
  (testing "phases 1-3: a governor-clean :commit still escalates (never auto)"
    (doseq [p [1 2 3]]
      (let [{:keys [disposition reason]} (phase/gate p {:op :tos/change-proposal} :commit)]
        (is (= :escalate disposition) (str "phase " p))
        (is (= :phase-approval reason) (str "phase " p))))))

(deftest governor-hold-always-wins
  (testing "a governor HOLD stays HOLD regardless of phase"
    (doseq [p [0 1 2 3]]
      (let [{:keys [disposition]} (phase/gate p {:op :tos/change-proposal} :hold)]
        (is (= :hold disposition))))))

(deftest governor-escalate-passes-through-when-writable
  (testing "a governor ESCALATE (e.g. high-stakes) passes through unchanged at phases where the op is writable"
    (doseq [p [1 2 3]]
      (let [{:keys [disposition]} (phase/gate p {:op :tos/change-proposal} :escalate)]
        (is (= :escalate disposition) (str "phase " p))))))

(deftest phase-0-downgrades-escalate-to-hold-too
  (testing "phase 0 is read-only: even a governor ESCALATE can't attempt a write, so it also holds"
    (let [{:keys [disposition reason]} (phase/gate 0 {:op :tos/change-proposal} :escalate)]
      (is (= :hold disposition))
      (is (= :phase-disabled reason)))))

(deftest verdict->disposition-mapping
  (is (= :hold (phase/verdict->disposition {:hard? true :escalate? false})))
  (is (= :escalate (phase/verdict->disposition {:hard? false :escalate? true})))
  (is (= :commit (phase/verdict->disposition {:hard? false :escalate? false}))))
