(ns friend-oauth2.workflow
  (:require
   [friend-oauth2.util :as util]
   [cemerick.friend :as friend]
   [clj-http.client :as client]
   [schema.core :as s]
   [ring.util.request :as request]))

(s/defschema ClientConfig {:client-id     String
                            :client-secret String
                            :callback {:domain String
                                       :path   String}})

(defn- default-credential-fn
  [creds]
  {:identity (:access-token creds)})

(defn- is-oauth2-callback?
  [config request]
  (or (= (request/path-info request)
         (get-in config [:client-config :callback :path]))
      (= (request/path-info request)
         (or (:login-uri config) (-> request ::friend/auth-config :login-uri)))))

(defn- request-token
  "POSTs request to OAauth2 provider for authorization token."
  [{:keys [uri-config access-token-parsefn]} code]
  (let [access-token-uri (:access-token-uri uri-config)
        query-map        (-> (util/replace-authz-code access-token-uri code)
                             (assoc :grant_type "authorization_code"))
        token-parse-fn   (or access-token-parsefn util/extract-access-token)]
    (token-parse-fn (client/post (:url access-token-uri) {:form-params query-map}))))

(defn- redirect-to-provider!
  "Redirects user to OAuth2 provider. Code should be in response."
  [{:keys [uri-config]} request]
  (let [anti-forgery-token    (util/generate-anti-forgery-token)
        session-with-af-token (assoc (:session request) :state anti-forgery-token)]
    (-> uri-config
        (util/format-authn-uri anti-forgery-token)
        ring.util.response/redirect
        (assoc :session session-with-af-token))))

(s/defn ^:always-validate workflow
  "Workflow for OAuth2"
  [config :- {(s/required-key :client-config) ClientConfig
               s/Any s/Any}];; The rest of config.
  (fn [request]
    (when (is-oauth2-callback? config request)
      ;; Extracts code from request if we are getting here via OAuth2 callback.
      ;; http://tools.ietf.org/html/draft-ietf-oauth-v2-31#section-4.1.2
      (let [{:keys [state code error]} (:params request)
            session-state        (util/extract-anti-forgery-token request)]
        (if (and (not (nil? code))
                 (= state session-state))
          (when-let [access-token (request-token config code)]
            (when-let [auth-map ((:credential-fn config default-credential-fn)
                                 {:access-token access-token})]
              (vary-meta auth-map merge {::friend/workflow :oauth2
                                         ::friend/redirect-on-auth? true
                                         :type ::friend/auth})))

          (let [auth-error-fn (:auth-error-fn config)]
            (if (and error auth-error-fn)
              (auth-error-fn error)
              (redirect-to-provider! config request))))))))
