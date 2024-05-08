(ns clojure-anthropic.core
  (:require
   [aleph.http :as http]
   [cheshire.core :as cheshire]
   [clj-commons.byte-streams :as bs]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [manifold.deferred :as d]
   [manifold.stream :as streaming]))

(def messages-endpoint "https://api.anthropic.com/v1/messages")

(def anthropic-api-version
  "2023-06-01")

(defn- make-body
  [system model max-tokens stream? messages]
  (cheshire/generate-string {:system system
                             :model model
                             :max_tokens max-tokens
                             :messages messages
                             :stream stream?}))

(defn- make-request
  [system api-key model max-tokens stream? messages]
  {:body (make-body system model max-tokens stream? messages)
   :headers {"x-api-key" api-key,
             "anthropic-version" anthropic-api-version}
   :content-type :json})

(defn- parse-anthropic-response
  [response]
  (d/chain response :body bs/to-reader #(cheshire/parse-stream % keyword)))

(defn- parse-sse-message
  [msg]
  (if-let [data-line (->> (str/split msg #"\n")
                          (filter #(str/starts-with? % "data: "))
                          (first))]
    (-> (str/replace data-line "data: " "")
        (cheshire/parse-string  keyword))
    nil))

(defn- parse-anthropic-streaming-response
  [response]
  (d/chain response
           :body
           bs/to-line-seq
           streaming/->source
           (partial streaming/map parse-sse-message)
           (partial streaming/filter some?)))

(defn messages
  [& {:keys [message-list system model api-key max-tokens async? stream?]
      :or {system "You are a helpful assistant"
           model "claude-3-sonnet-20240229"
           api-key (System/getenv "ANTHROPIC_API_KEY")
           max-tokens 1024
           async? false
           stream? false}
      :as arguments}]
  (try (let [base-request (make-request system api-key model max-tokens stream? message-list)]
         (if async?
           (if stream?
             (-> (http/post messages-endpoint base-request)
                 (parse-anthropic-streaming-response))
             (-> (http/post messages-endpoint  base-request)
                 (parse-anthropic-response)))
           @(messages (assoc arguments :async? true))))
       (catch Exception e
         (let [body (-> (ex-data e)
                        (:body))]
           (cond
             (instance? java.io.ByteArrayInputStream body) (->> (io/reader body)
                                                                (cheshire/parse-stream)
                                                                (ex-info "Error contacting Anthropic")
                                                                (throw))
             :else (throw e))))))

(comment
  (let [api-token (str/trim (slurp ".dev_token"))
        payload [{:role "user" :content "hello, claude!"}]
        stream (messages :message-list payload :api-key api-token :stream? true  :model "claude-3-opus-20240229")]
    (streaming/consume #(prn %) stream)))
