(ns status-im.models.transactions
  (:require [clojure.set :as set]
            [status-im.utils.datetime :as time]
            [status-im.utils.ethereum.core :as ethereum]
            [status-im.utils.ethereum.tokens :as tokens]
            [status-im.utils.semaphores :as semaphores]
            [taoensso.timbre :as log]
            [status-im.utils.fx :as fx]))

(def sync-interval-ms 15000)
(def confirmations-count-threshold 12)

;; Detects if some of the transactions have less than 12 confirmations
(defn- have-unconfirmed-transactions? [cofx]
  (->> (get-in cofx [:db :wallet :transactions])
       vals
       (map :confirmations)
       (map int)
       (some #(< % confirmations-count-threshold))))

(defn- wallet-transactions-set [db]
  (-> db
      (get-in [:wallet :transactions])
      keys
      set))

;; Detects if some of missing chat transactions are missing from wallet
(defn- have-missing-chat-transactions? [{:keys [db]}]
  (let [chat-transactions (get-in db [:wallet :chat-transactions])]
    (not= (count chat-transactions)
          (count (set/intersection
                  chat-transactions
                  (wallet-transactions-set db))))))

(fx/defn schedule-sync [cofx]
  {:utils/dispatch-later [{:ms       sync-interval-ms
                           :dispatch [:sync-wallet-transactions]}]})

(defn store-chat-transaction-hash [tx-hash {:keys [db]}]
  {:db (update-in db [:wallet :chat-transactions] conj tx-hash)})

(defn- missing-chat-transactions [{:keys [db] :as cofx}]
  (let [chat-transactions (->> db
                               :chats
                               vals
                               (remove :public?)
                               (mapcat :messages)
                               vals
                               flatten
                               (filter #(= "command" (:content-type %)))
                               (map #(get-in % [:content :params :tx-hash]))
                               (filter identity)
                               set)]
    (set/difference
     chat-transactions
     (wallet-transactions-set db))))

(fx/defn load-missing-chat-transactions
  "Find missing chat transactions and store them at [:wallet :chat-transactions]
  to be used later by have-missing-chat-transactions? on every sync request"
  [{:keys [db] :as cofx}]
  (when (nil? (get-in db [:wallet :chat-transactions]))
    {:db (assoc-in db
                   [:wallet :chat-transactions]
                   (missing-chat-transactions cofx))}))

(fx/defn run-update [{{:keys [network network-status web3] :wallet/keys [all-tokens] :as db} :db}]
  (when (not= network-status :offline)
    (let [network (get-in db [:account/account :networks network])
          chain (ethereum/network->chain-keyword network)]
      (when-not (= :custom chain)
        (let [all-tokens (tokens/tokens-for all-tokens chain)
              token-addresses (map :address all-tokens)]
          (log/debug "Syncing transactions data..")
          {:get-transactions {:account-id      (get-in db [:account/account :address])
                              :token-addresses token-addresses
                              :chain           chain
                              :web3            web3
                              :all-tokens      all-tokens
                              :success-event   :update-transactions-success
                              :error-event     :update-transactions-fail}
           :db               (-> db
                                 (update-in [:wallet :errors] dissoc :transactions-update)
                                 (assoc-in [:wallet :transactions-loading?] true)
                                 (assoc-in [:wallet :transactions-last-updated-at] (time/timestamp)))})))))

(defn- time-to-sync? [cofx]
  (let [last-updated-at (get-in cofx [:db :wallet :transactions-last-updated-at])]
    (or (nil? last-updated-at)
        (< sync-interval-ms
           (- (time/timestamp) last-updated-at)))))

(fx/defn sync
  "Fetch updated data for any unconfirmed transactions or incoming chat transactions missing in wallet
  and schedule new recurring sync request"
  [{:keys [db] :as cofx}]
  (if (:account/account db)
    (let [in-progress? (get-in db [:wallet :transactions-loading?])
          {:keys [app-state network-status]} db]
      (if (and (not= network-status :offline)
               (= app-state "active")
               (not in-progress?)
               (time-to-sync? cofx)
               (or (have-unconfirmed-transactions? cofx)
                   (have-missing-chat-transactions? cofx)))
        (fx/merge cofx
                  (run-update)
                  (schedule-sync))
        (schedule-sync cofx)))
    (semaphores/free cofx :sync-wallet-transactions?)))

(fx/defn start-sync [cofx]
  (when-not (semaphores/locked? cofx :sync-wallet-transactions?)
    (fx/merge cofx
              (load-missing-chat-transactions)
              (semaphores/lock :sync-wallet-transactions?)
              (sync))))
