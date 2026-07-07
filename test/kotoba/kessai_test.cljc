(ns kotoba.kessai-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.banking :as banking]
            [kotoba.kessai :as kessai]))

(deftest payment-ref-test
  (let [pref (kessai/payment-ref :card :authorized 1999 "USD")]
    (is (= :card (:kessai/rail pref)))
    (is (= :authorized (:kessai/status pref)))
    (is (kessai/authorized? pref))
    (is (kessai/approved? pref))
    (is (not (kessai/captured? pref)))))

(deftest mock-payment-port-test
  (let [port (kessai/mock-payment-port)
        request {:kessai/rail :card :kessai/amount 1999 :kessai/currency "USD"
                  :kessai/instrument {:card/network :visa}}]
    (testing "authorize"
      (let [pref (kessai/authorize port request)]
        (is (kessai/authorized? pref))
        (is (= 1999 (:kessai/amount pref)))
        (testing "capture"
          (let [captured (kessai/capture port pref)]
            (is (kessai/captured? captured))
            (testing "full refund"
              (is (= :refunded (:kessai/status (kessai/refund port captured 1999)))))
            (testing "partial refund"
              (is (= :partially-refunded (:kessai/status (kessai/refund port captured 500)))))))
        (testing "void"
          (is (kessai/voided? (kessai/void port pref))))))
    (testing "capture on a non-authorized ref is a no-op"
      (let [declined (kessai/payment-ref :card :declined 1999 "USD")]
        (is (kessai/declined? (kessai/capture port declined)))))))

(deftest settlement-entries-test
  (let [captured (kessai/payment-ref :wire :captured 1999 "USD" :ref "wire_1")]
    (testing "captured payment produces balanced entries"
      (let [entries (kessai/settlement-entries captured "clearing-1" "merchant-1")]
        (is (= 2 (count entries)))
        (is (banking/balanced? entries))
        (is (= "clearing-1" (:ledger/account (first entries))))
        (is (= :debit (:ledger/side (first entries))))
        (is (= "merchant-1" (:ledger/account (second entries))))
        (is (= :credit (:ledger/side (second entries))))))
    (testing "posting is balanced"
      (let [posting (kessai/settlement-posting "posting-1" captured "clearing-1" "merchant-1")]
        (is (:ledger/balanced? posting))
        (is (not (:ledger/unbalanced posting))))))
  (testing "non-captured payment produces no entries or posting"
    (let [authorized (kessai/payment-ref :wire :authorized 1999 "USD")]
      (is (nil? (kessai/settlement-entries authorized "clearing-1" "merchant-1")))
      (is (nil? (kessai/settlement-posting "posting-2" authorized "clearing-1" "merchant-1"))))))
