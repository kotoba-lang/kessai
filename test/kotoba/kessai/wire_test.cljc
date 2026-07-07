(ns kotoba.kessai.wire-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.kessai.wire :as wire]))

;; Textbook example IBANs (mod-97 verified) — GB/DE — with real-format
;; (not necessarily real-institution) BICs of the matching country.
(def debtor-iban "GB82WEST12345698765432")
(def debtor-bic "NWBKGB2L")
(def creditor-iban "DE89370400440532013000")
(def creditor-bic "DEUTDEFF")

(deftest bic-valid-test
  (testing "accepts 8-char BIC"
    (is (wire/bic-valid? debtor-bic)))
  (testing "accepts 11-char BIC with branch code"
    (is (wire/bic-valid? "DEUTDEFF500")))
  (testing "rejects malformed input"
    (is (not (wire/bic-valid? "not-a-bic"))))
  (testing "rejects wrong length"
    (is (not (wire/bic-valid? "DEUT")))))

(deftest parse-bic-test
  (let [b (wire/parse-bic creditor-bic)]
    (is (= "DEUT" (:bic/institution b)))
    (is (= "DE" (:bic/country b)))
    (is (= "FF" (:bic/location b)))
    (is (nil? (:bic/branch b))))
  (is (nil? (wire/parse-bic "bad"))))

(defn- valid-fields []
  {:msg-id "MSG1" :end-to-end-id "E2E1"
   :debtor-iban debtor-iban :debtor-bic debtor-bic
   :creditor-iban creditor-iban :creditor-bic creditor-bic
   :creditor-name "Acme GmbH" :amount 100.5 :currency "EUR"})

(deftest credit-transfer-test
  (testing "valid fields construct a pain.001 record"
    (let [ct (wire/credit-transfer (valid-fields))]
      (is (= "pain.001.001.09" (:iso20022/msg-type ct)))
      (is (= debtor-iban (:iban (:iso20022/debtor ct))))
      (is (= creditor-bic (:bic (:iso20022/creditor ct))))))
  (testing "invalid IBAN throws"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (wire/credit-transfer (assoc (valid-fields) :debtor-iban "GB00BAD")))))
  (testing "invalid BIC throws"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (wire/credit-transfer (assoc (valid-fields) :debtor-bic "bad")))))
  (testing "invalid amount throws"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (wire/credit-transfer (assoc (valid-fields) :amount -5))))))

(deftest pain001-xml-test
  (let [xml (wire/->pain001-xml (wire/credit-transfer (valid-fields)))]
    (testing "structurally valid pain.001 XML"
      (is (re-find #"<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain\.001\.001\.09\">" xml))
      (is (re-find (re-pattern (str "<IBAN>" debtor-iban "</IBAN>")) xml))
      (is (re-find (re-pattern (str "<IBAN>" creditor-iban "</IBAN>")) xml))
      (is (re-find #"<BICFI>NWBKGB2L</BICFI>" xml))
      (is (re-find #"<EndToEndId>E2E1</EndToEndId>" xml))
      (is (re-find #"Ccy=\"EUR\"" xml)))))

(deftest wire-request-test
  (let [req (wire/wire-request (wire/credit-transfer (valid-fields)))]
    (is (= :wire (:kessai/rail req)))
    (is (= 100.5 (:kessai/amount req)))
    (is (= "EUR" (:kessai/currency req)))))
