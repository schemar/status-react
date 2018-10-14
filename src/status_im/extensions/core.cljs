(ns status-im.extensions.core
  (:require [clojure.string :as string]
            [re-frame.core :as re-frame]
            [pluto.reader :as reader]
            [pluto.storages :as storages]
            [status-im.chat.commands.core :as commands]
            [status-im.chat.commands.impl.transactions :as transactions]
            [status-im.ui.components.react :as react]
            [status-im.ui.components.button.view :as button]
            [status-im.utils.handlers :as handlers]))

(re-frame/reg-fx
 ::alert
 (fn [value] (js/alert value)))

(re-frame/reg-event-fx
 :alert
 (fn [_ [_ {:keys [value]}]]
   {::alert value}))

(re-frame/reg-fx
 ::log
 (fn [value] (js/console.log value)))

(re-frame/reg-event-fx
 :log
 (fn [_ [_ {:keys [value]}]]
   {::log value}))

(re-frame/reg-sub
 :store/get
 (fn [db [_ {:keys [key]}]]
   (get-in db [:extensions-store :collectible key])))

(handlers/register-handler-fx
 :store/put
 (fn [{:keys [db]} [_ {:keys [key value]}]]
   {:db (assoc-in db [:extensions-store :collectible key] value)}))

(handlers/register-handler-fx
 :store/append
 (fn [{:keys [db]} [_ {:keys [key value]}]]
   {:db (update-in db [:extensions-store :collectible] dissoc key)}))

(handlers/register-handler-fx
 :store/clear
 (fn [{:keys [db]} [_ {:keys [key]}]]
   {:db (update-in db [:extensions-store :collectible] dissoc key)}))

(re-frame/reg-event-fx
 :http/get
 (fn [_ [_ {:keys [url on-success on-failure timeout]}]]
   {:http-get (merge {:url url
                      :success-event-creator (fn [o] (into on-success (vector o)))}
                     (when on-failure
                       {:failure-event-creator (fn [o] (into on-failure (vector o)))})
                     (when timeout
                       {:timeout-ms timeout}))}))

(defn button [{:keys [on-click]} label]
  [button/secondary-button {:on-press #(re-frame/dispatch on-click)} label])

(defn input [{:keys [on-change placeholder]}]
  [react/text-input {:on-change-text #(re-frame/dispatch on-change) :placeholder placeholder}])

(def capacities
  {:components {'view               {:value react/view}
                'text               {:value react/text}
                'input              {:value input :properties {:on-change :event :placeholder :string}}
                'button             {:value button :properties {:on-click :event}}
                'nft-token-viewer   {:value transactions/nft-token :properties {:token :string}}
                'transaction-status {:value transactions/transaction-status :properties {:outgoing :string :tx-hash :string}}
                'asset-selector     {:value transactions/choose-nft-asset-suggestion}
                'token-selector     {:value transactions/choose-nft-token-suggestion}}
   :queries    {'store/get {:value :store/get :arguments {:key :string}}
                'get-collectible-token {:value :get-collectible-token :arguments {:token :string :symbol :string}}}
   :events     {'alert
                {:permissions [:read]
                 :value       :alert
                 :arguments   {:value :string}}
                'log
                {:permissions [:read]
                 :value       :log
                 :arguments   {:value :string}}
                'store/put
                {:permissions [:read]
                 :value       :store/put
                 :arguments   {:key :string :value :string}}
                'store/append
                {:permissions [:read]
                 :value       :store/append
                 :arguments   {:key :string :value :string}}
                'store/clear
                {:permissions [:read]
                 :value       :store/put
                 :arguments   {:key :string}}
                'http/get
                {:permissions [:read]
                 :value       :http/get
                 :arguments   {:url        :string
                               :timeout    :string
                               :on-success :event
                               :on-failure :event}}
                'browser/open {:value  :browser/open :arguments {:url :string}}
                'chat/open {:value  :chat/open :arguments {:url :string}}
                'ethereum/sign
                {:arguments
                 {:account   :string
                  :message   :string
                  :on-result :event}}
                'ethereum/send-raw-transaction
                {:arguments {:data :string}}
                'ethereum/send-transaction
                {:arguments
                 {:from       :string
                  :to         :string
                  :gas?       :string
                  :gas-price? :string
                  :value?     :string
                  :data?      :string
                  :nonce?     :string}}
                'ethereum/new-contract
                {:arguments
                 {:from       :string
                  :gas?       :string
                  :gas-price? :string
                  :value?     :string
                  :data?      :string
                  :nonce?     :string}}
                'ethereum/call
                {:arguments
                 {:from?      :string
                  :to         :string
                  :gas?       :string
                  :gas-price? :string
                  :value?     :string
                  :data?      :string
                  :block      :string}}
                'ethereum/logs
                {:arguments
                 {:from?     :string
                  :to        :string
                  :address   :string
                  :topics    :string
                  :blockhash :string}}}
   :hooks {:commands commands/command-hook}})

(defn read-extension [{:keys [value]}]
  (when (seq value)
    (let [{:keys [content]} (first value)]
      (reader/read content))))

(defn parse [{:keys [data]}]
  (try
    (let [{:keys [errors] :as extension-data} (reader/parse {:capacities capacities} data)]
      (when errors
        (println "Failed to parse status extensions" errors))
      extension-data)
    (catch :default e (println "EXC" e))))

(def uri-prefix "https://get.status.im/extension/")

(defn valid-uri? [s]
  (boolean
   (when s
     (re-matches (re-pattern (str "^" uri-prefix "\\w+@\\w+")) (string/trim s)))))

(defn url->uri [s]
  (when s
    (string/replace s uri-prefix "")))

(defn load-from [url f]
  (when-let [uri (url->uri url)]
    (storages/fetch uri f)))
