(ns kotoba.kessai.wire
  "Wire/SWIFT rail for kessai — ISO 20022 pain.001 (CustomerCreditTransferInitiation),
  bridging kotoba.swift's BIC (ISO 9362) validation and kotoba.banking's IBAN
  (ISO 13616) to kotoba.kessai's rail-agnostic PaymentRef. Does not
  reimplement BIC validation — that structural model already exists in
  kotoba-lang/swift and is reused as-is.

  Models a single credit-transfer instruction only (one PmtInf / one
  CdtTrfTxInf) — batched multi-transaction pain.001 messages, the pacs.008
  interbank leg, and camt.053 statements are a follow-up (see README).

  No network, no I/O — a real wire adapter sends the XML this namespace
  renders to a bank/SWIFT gateway, but that transport is a follow-up."
  (:require [clojure.string :as str]
            [kotoba.banking :as banking]
            [kotoba.swift :as swift]))

;; ---------------------------------------------------------------------------
;; pain.001.001.09 — CustomerCreditTransferInitiation (single transaction)
;; ---------------------------------------------------------------------------

(defn credit-transfer
  "Construct a single-transaction ISO 20022 pain.001 credit-transfer record.
  `msg-id`/`end-to-end-id` are caller-supplied idempotency keys; amount is a
  plain positive number in major units (e.g. 12.50); currency is an ISO 4217
  alpha-3 code. IBANs are validated via kotoba.banking/iban-valid?, BICs via
  kotoba.swift/bic-valid?. Throws ex-info with {:kessai/errors [...]} when
  any IBAN/BIC/amount/currency fails validation."
  [{:keys [msg-id end-to-end-id debtor-iban debtor-bic
           creditor-iban creditor-bic creditor-name amount currency]
    :as   fields}]
  (let [errors (cond-> []
                 (not (banking/iban-valid? debtor-iban))         (conj :bad-debtor-iban)
                 (not (banking/iban-valid? creditor-iban))       (conj :bad-creditor-iban)
                 (not (swift/bic-valid? debtor-bic))             (conj :bad-debtor-bic)
                 (not (swift/bic-valid? creditor-bic))           (conj :bad-creditor-bic)
                 (not (and (number? amount) (pos? amount)))      (conj :bad-amount)
                 (not (re-matches #"[A-Z]{3}" (or currency "")))  (conj :bad-currency))]
    (when (seq errors)
      (throw (ex-info "invalid credit transfer" {:kessai/errors errors :kessai/fields fields})))
    {:iso20022/msg-type      "pain.001.001.09"
     :iso20022/msg-id        msg-id
     :iso20022/end-to-end-id end-to-end-id
     :iso20022/debtor        {:iban debtor-iban :bic debtor-bic}
     :iso20022/creditor      {:iban creditor-iban :bic creditor-bic :name creditor-name}
     :iso20022/amount        amount
     :iso20022/currency      currency}))

(defn- xml-escape
  "Escape text for placement inside XML element content or a double-quoted
  attribute value. IBAN/BIC/currency are structurally validated elsewhere
  and never reach here unescaped, but msg-id/end-to-end-id/creditor-name are
  caller-supplied free text with no character restrictions -- splicing them
  in raw lets a bare `&`, `<`, or `>` break XML well-formedness, or worse,
  let a crafted value (e.g. \"</Nm></Cdtr><CdtrAcct>...\") inject/override
  sibling elements."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn ->pain001-xml
  "Minimal, structurally-valid pain.001.001.09 XML for a single credit
  transfer (one GrpHdr + one PmtInf + one CdtTrfTxInf) — enough to
  interoperate with bank/SWIFT gateways that accept single-transaction
  batches. Not a full XSD implementation; see README."
  [{:iso20022/keys [msg-id end-to-end-id debtor creditor amount currency]}]
  (let [msg-id        (xml-escape msg-id)
        end-to-end-id (xml-escape end-to-end-id)
        creditor-name (xml-escape (:name creditor))]
    (str
     "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
     "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.001.001.09\">"
     "<CstmrCdtTrfInitn>"
     "<GrpHdr>"
     "<MsgId>" msg-id "</MsgId>"
     "<CreDtTm/>"
     "<NbOfTxs>1</NbOfTxs>"
     "<CtrlSum>" amount "</CtrlSum>"
     "</GrpHdr>"
     "<PmtInf>"
     "<PmtInfId>" msg-id "</PmtInfId>"
     "<PmtMtd>TRF</PmtMtd>"
     "<Dbtr><Nm/></Dbtr>"
     "<DbtrAcct><Id><IBAN>" (:iban debtor) "</IBAN></Id></DbtrAcct>"
     "<DbtrAgt><FinInstnId><BICFI>" (:bic debtor) "</BICFI></FinInstnId></DbtrAgt>"
     "<CdtTrfTxInf>"
     "<PmtId><EndToEndId>" end-to-end-id "</EndToEndId></PmtId>"
     "<Amt><InstdAmt Ccy=\"" currency "\">" amount "</InstdAmt></Amt>"
     "<CdtrAgt><FinInstnId><BICFI>" (:bic creditor) "</BICFI></FinInstnId></CdtrAgt>"
     "<Cdtr><Nm>" creditor-name "</Nm></Cdtr>"
     "<CdtrAcct><Id><IBAN>" (:iban creditor) "</IBAN></Id></CdtrAcct>"
     "</CdtTrfTxInf>"
     "</PmtInf>"
     "</CstmrCdtTrfInitn>"
     "</Document>")))

;; ---------------------------------------------------------------------------
;; kessai bridge
;; ---------------------------------------------------------------------------

(defn wire-request
  "Adapt a pain.001 credit-transfer record into a :wire rail authorize
  request, ready to pass to kotoba.kessai/authorize."
  [credit-transfer]
  {:kessai/rail       :wire
   :kessai/amount     (:iso20022/amount credit-transfer)
   :kessai/currency   (:iso20022/currency credit-transfer)
   :kessai/instrument (:iso20022/creditor credit-transfer)
   :iso20022/message  credit-transfer})
