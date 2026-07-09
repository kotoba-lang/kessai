(ns kotoba.kessai.wire-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.kessai.wire :as wire]))

;; Textbook example IBANs (mod-97 verified) — GB/DE — with real-format
;; (not necessarily real-institution) BICs of the matching country. BIC
;; shape/checksum-free validation itself is kotoba-lang/swift's job
;; (kotoba.swift/bic-valid?) — not re-tested here, only its integration.
(def debtor-iban "GB82WEST12345698765432")
(def debtor-bic "NWBKGB2L")
(def creditor-iban "DE89370400440532013000")
(def creditor-bic "DEUTDEFF")

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

(deftest pain001-xml-escapes-free-text-fields
  ;; msg-id, end-to-end-id, and creditor-name are caller-supplied free text
  ;; with no character restrictions -- a bare &, <, or > must not break XML
  ;; well-formedness or let a crafted value inject/override sibling elements.
  (let [xml (wire/->pain001-xml
             (wire/credit-transfer
              (assoc (valid-fields) :creditor-name "Acme & Sons <Ltd>")))]
    (testing "the & and < > are escaped, not spliced raw"
      (is (re-find #"<Nm>Acme &amp; Sons &lt;Ltd&gt;</Nm>" xml))
      (is (not (re-find #"&(?!amp;|lt;|gt;|quot;|#)" xml))
          "no bare, unescaped & anywhere in the document")))
  (testing "an element-injection attempt via creditor-name is neutralized"
    (let [xml (wire/->pain001-xml
               (wire/credit-transfer
                (assoc (valid-fields)
                       :creditor-name "</Nm></Cdtr><CdtrAcct><Id><IBAN>ATTACKER</IBAN></Id></CdtrAcct>")))]
      (is (not (re-find #"<IBAN>ATTACKER</IBAN>" xml))
          "the injected IBAN element never becomes real XML markup")
      (is (re-find (re-pattern (str "<IBAN>" creditor-iban "</IBAN>")) xml)
          "the legitimate creditor IBAN element is still intact"))))

(deftest wire-request-test
  (let [req (wire/wire-request (wire/credit-transfer (valid-fields)))]
    (is (= :wire (:kessai/rail req)))
    (is (= 100.5 (:kessai/amount req)))
    (is (= "EUR" (:kessai/currency req)))))
