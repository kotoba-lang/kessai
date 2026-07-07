(ns kotoba.kessai.wire
  "Wire/SWIFT rail for kessai — ISO 20022 pain.001 (CustomerCreditTransferInitiation)
  and BIC (ISO 9362) validation, bridging kotoba.banking's IBAN (ISO 13616) to
  kotoba.kessai's rail-agnostic PaymentRef.

  Models a single credit-transfer instruction only (one PmtInf / one
  CdtTrfTxInf) — batched multi-transaction pain.001 messages, the pacs.008
  interbank leg, and camt.053 statements are a follow-up (see README).

  No network, no I/O — a real wire adapter sends the XML this namespace
  renders to a bank/SWIFT gateway, but that transport is a follow-up."
  (:require [clojure.string :as str]
            [kotoba.banking :as banking]))

;; ---------------------------------------------------------------------------
;; BIC / SWIFT code (ISO 9362)
;;   shape: institution(4 letters) + country(2 letters, ISO 3166-1 alpha-2) +
;;          location(2 alnum) [+ branch(3 alnum)]  => 8 or 11 chars
;; ---------------------------------------------------------------------------

(def ^:private bic-pattern #"^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$")

(defn bic-valid?
  "True when s is a well-formed BIC/SWIFT code (ISO 9362, 8 or 11 chars)."
  [s]
  (boolean (and (string? s) (re-matches bic-pattern (str/upper-case s)))))

(defn parse-bic
  "Decompose a BIC into {:bic/institution :bic/country :bic/location
  :bic/branch}. Returns nil when malformed."
  [s]
  (when (bic-valid? s)
    (let [u (str/upper-case s)]
      {:bic/institution (subs u 0 4)
       :bic/country     (subs u 4 6)
       :bic/location    (subs u 6 8)
       :bic/branch      (when (> (count u) 8) (subs u 8 11))})))

;; ---------------------------------------------------------------------------
;; pain.001.001.09 — CustomerCreditTransferInitiation (single transaction)
;; ---------------------------------------------------------------------------

(defn credit-transfer
  "Construct a single-transaction ISO 20022 pain.001 credit-transfer record.
  `msg-id`/`end-to-end-id` are caller-supplied idempotency keys; amount is a
  plain positive number in major units (e.g. 12.50); currency is an ISO 4217
  alpha-3 code. Throws ex-info with {:kessai/errors [...]} when any
  IBAN/BIC/amount/currency fails validation."
  [{:keys [msg-id end-to-end-id debtor-iban debtor-bic
           creditor-iban creditor-bic creditor-name amount currency]
    :as   fields}]
  (let [errors (cond-> []
                 (not (banking/iban-valid? debtor-iban))         (conj :bad-debtor-iban)
                 (not (banking/iban-valid? creditor-iban))       (conj :bad-creditor-iban)
                 (not (bic-valid? debtor-bic))                   (conj :bad-debtor-bic)
                 (not (bic-valid? creditor-bic))                 (conj :bad-creditor-bic)
                 (not (and (number? amount) (pos? amount)))      (conj :bad-amount)
                 (not (re-matches #"[A-Z]{3}" (or currency "")))  (conj :bad-currency))]
    (when (seq errors)
      (throw (ex-info "invalid credit transfer" {:kessai/errors errors :kessai/fields fields})))
    {:iso20022/msg-type      "pain.001.001.09"
     :iso20022/msg-id        msg-id
     :iso20022/end-to-end-id end-to-end-id
     :iso20022/debtor        {:iban (str/upper-case debtor-iban) :bic (str/upper-case debtor-bic)}
     :iso20022/creditor      {:iban (str/upper-case creditor-iban) :bic (str/upper-case creditor-bic)
                               :name creditor-name}
     :iso20022/amount        amount
     :iso20022/currency      currency}))

(defn ->pain001-xml
  "Minimal, structurally-valid pain.001.001.09 XML for a single credit
  transfer (one GrpHdr + one PmtInf + one CdtTrfTxInf) — enough to
  interoperate with bank/SWIFT gateways that accept single-transaction
  batches. Not a full XSD implementation; see README."
  [{:iso20022/keys [msg-id end-to-end-id debtor creditor amount currency]}]
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
   "<Cdtr><Nm>" (:name creditor) "</Nm></Cdtr>"
   "<CdtrAcct><Id><IBAN>" (:iban creditor) "</IBAN></Id></CdtrAcct>"
   "</CdtTrfTxInf>"
   "</PmtInf>"
   "</CstmrCdtTrfInitn>"
   "</Document>"))

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
